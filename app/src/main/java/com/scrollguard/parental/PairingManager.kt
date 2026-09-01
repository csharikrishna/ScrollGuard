package com.scrollguard.parental

import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

/**
 * Handles the pairing handshake between parent and child devices.
 *
 * **Child flow:** generates a `families/{familyId}` doc and a `pairing/{code}` doc,
 * then displays the code. The child waits for the parent to claim it.
 *
 * **Parent flow:** enters the pairing code, a Firestore transaction validates
 * (exists, not expired, not consumed), binds `parentUid`, marks consumed.
 *
 * Codes are 6-character alphanumeric, 5-minute TTL, single-use (transactional).
 */
object PairingManager {

    private const val TAG = "PairingManager"
    private const val PAIRING_CODE_LENGTH = 6
    private const val PAIRING_TTL_MS = 5 * 60 * 1000L // 5 minutes

    /** Firestore write Tasks don't resolve until the server ACKs, even with offline persistence
     *  enabled — offline, an unwrapped .await() here suspends forever, leaving the caller's UI
     *  stuck on a permanent loading spinner with no way to fail. */
    private const val FIRESTORE_OP_TIMEOUT_MS = 10_000L

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Generates a random alphanumeric pairing code.
     */
    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // No 0/O/1/I to avoid confusion
        val random = SecureRandom()
        return (1..PAIRING_CODE_LENGTH).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * Child-side: creates a family document and a pairing code.
     * Returns (familyId, pairingCode) on success.
     */
    suspend fun generatePairingCode(childUid: String): Result<Pair<String, String>> {
        return try {
            withTimeout(FIRESTORE_OP_TIMEOUT_MS) {
                // Create the family document.
                val familyRef = firestore.collection("families").document()
                val familyId = familyRef.id
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

                familyRef.set(mapOf(
                    "childUid" to childUid,
                    "parentUid" to null,
                    "childDeviceName" to deviceName,
                    "createdAt" to FieldValue.serverTimestamp()
                )).await()

                // Create the initial config subcollection.
                familyRef.collection("config").document("current").set(mapOf(
                    "enabled" to false,
                    "configVersion" to 0,
                    "updatedAt" to FieldValue.serverTimestamp()
                )).await()

                // Create the initial status subcollection.
                // accessibilityHealthy defaults optimistic here (no real status pushed yet) — the
                // first real pushStatus() call overwrites it with TimerState's actual value.
                familyRef.collection("status").document("current").set(mapOf(
                    "consumedEpochDay" to 0,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "accessibilityHealthy" to true
                )).await()

                // Create the pairing code.
                val code = generateCode()
                val pairingRef = firestore.collection("pairing").document(code)
                pairingRef.set(mapOf(
                    "familyId" to familyId,
                    "parentUid" to null,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to com.google.firebase.Timestamp(
                        java.util.Date(System.currentTimeMillis() + PAIRING_TTL_MS)
                    ),
                    "consumed" to false
                )).await()

                // Track the currently-issued code on the family doc so a later regenerate/unpair
                // can delete this SPECIFIC doc by direct ID — the pairing collection's rules
                // deliberately grant no `list` access (to block enumeration/harvesting of pending
                // codes), so a "query by familyId" cleanup isn't something the rules will ever
                // allow, by design. A known-ID pointer is the only safe way to target it.
                familyRef.update("currentPairingCode", code).await()

                Log.i(TAG, "Pairing code generated")
                Result.success(Pair(familyId, code))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate pairing code", e)
            Result.failure(e)
        }
    }

    /**
     * Child-side: generates a fresh pairing code for a family that already exists — used when a
     * child returns to the pairing screen after the original code was lost (e.g. the Activity
     * was left and reopened before the parent claimed it, so the in-memory code is gone; there
     * was previously no way to recover from this short of unpairing and starting over).
     */
    suspend fun regeneratePairingCode(familyId: String): Result<String> {
        return try {
            withTimeout(FIRESTORE_OP_TIMEOUT_MS) {
                val familyRef = firestore.collection("families").document(familyId)

                // Delete the previously-issued code (if any) before minting a new one — without
                // this, a child bouncing off this screen and hitting "Get a new code" repeatedly
                // left every prior unclaimed code behind forever (each is 5-min TTL but never
                // itself deleted). Best-effort: if this specific delete fails for some reason,
                // still proceed with issuing a working new code rather than blocking on cleanup.
                val previousCode = familyRef.get().await().getString("currentPairingCode")
                if (previousCode != null) {
                    try {
                        firestore.collection("pairing").document(previousCode).delete().await()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete previous pairing code (non-fatal)", e)
                    }
                }

                val code = generateCode()
                firestore.collection("pairing").document(code).set(mapOf(
                    "familyId" to familyId,
                    "parentUid" to null,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to com.google.firebase.Timestamp(
                        java.util.Date(System.currentTimeMillis() + PAIRING_TTL_MS)
                    ),
                    "consumed" to false
                )).await()
                familyRef.update("currentPairingCode", code).await()

                Result.success(code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to regenerate pairing code", e)
            Result.failure(e)
        }
    }

    /**
     * Parent-side: claims a pairing code via atomic Firestore transaction.
     * Returns the familyId on success.
     */
    suspend fun claimPairingCode(code: String, parentUid: String): Result<String> {
        return try {
            val pairingRef = firestore.collection("pairing").document(code.uppercase())

            val familyId = firestore.runTransaction { transaction ->
                val pairingDoc = transaction.get(pairingRef)

                if (!pairingDoc.exists()) {
                    throw Exception("Invalid pairing code")
                }

                val consumed = pairingDoc.getBoolean("consumed") ?: false
                if (consumed) {
                    throw Exception("Pairing code already used")
                }

                val expiresAt = pairingDoc.getTimestamp("expiresAt")
                if (expiresAt != null && expiresAt.toDate().time < System.currentTimeMillis()) {
                    throw Exception("Pairing code expired")
                }

                val familyId = pairingDoc.getString("familyId")
                    ?: throw Exception("Pairing code has no family reference")

                // Mark code as consumed.
                transaction.update(pairingRef, mapOf(
                    "consumed" to true,
                    "parentUid" to parentUid
                ))

                // Bind parent UID into the family document.
                val familyRef = firestore.collection("families").document(familyId)
                transaction.update(familyRef, "parentUid", parentUid)

                familyId
            }.await()

            Log.i(TAG, "Pairing code claimed")
            Result.success(familyId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to claim pairing code", e)
            Result.failure(e)
        }
    }

    /**
     * Unpairs the child from the family and deletes all of that family's data.
     *
     * Firestore does not cascade-delete subcollections when a parent document is deleted — a
     * document delete only ever removes that one document. Deleting just the family doc (the
     * previous behavior) silently orphaned config/current (+ its apps subcollection),
     * status/current, catalog/current, and every doc under requests/ — a real privacy gap, since
     * both parent and child are told "Unpaired" as if the relationship's data were gone.
     */
    suspend fun unpair(familyId: String): Result<Unit> {
        return try {
            val familyRef = firestore.collection("families").document(familyId)

            // Clean up the family's pairing code (claimed or not — either way it's dead once
            // unpaired) by its tracked ID. The pairing collection's rules grant no `list` access
            // (enumeration is deliberately blocked), so this direct-by-ID delete is the only safe
            // way to reach it; best-effort so a failure here doesn't block the rest of unpair.
            val currentCode = familyRef.get().await().getString("currentPairingCode")
            if (currentCode != null) {
                try {
                    firestore.collection("pairing").document(currentCode).delete().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete pairing code during unpair (non-fatal)", e)
                }
            }

            deleteCollection(familyRef.collection("config").document("current").collection("apps"))
            familyRef.collection("config").document("current").delete().await()
            familyRef.collection("status").document("current").delete().await()
            familyRef.collection("catalog").document("current").delete().await()
            deleteCollection(familyRef.collection("requests"))
            familyRef.delete().await()
            Log.i(TAG, "Unpaired and cleared family data")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpair", e)
            Result.failure(e)
        }
    }

    /** Deletes every document in a collection. Fine at this app's per-family scale (at most a
     *  handful of restricted apps / pending requests) — not intended for large collections. */
    private suspend fun deleteCollection(collection: com.google.firebase.firestore.CollectionReference) {
        val docs = collection.get().await()
        for (doc in docs.documents) {
            doc.reference.delete().await()
        }
    }

    /**
     * Checks if the pairing is complete (parent has claimed the code).
     * Returns the parent UID if paired, null otherwise.
     */
    suspend fun checkPairingStatus(familyId: String): String? {
        return try {
            val doc = firestore.collection("families").document(familyId).get().await()
            doc.getString("parentUid")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check pairing status", e)
            null
        }
    }
}
