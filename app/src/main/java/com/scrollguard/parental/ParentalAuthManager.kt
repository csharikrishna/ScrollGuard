package com.scrollguard.parental

import android.util.Log
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

/**
 * Wrapper around Firebase Auth for parental control identity management.
 *
 * - **Child devices** use anonymous auth (no email/password required).
 * - **Parent devices** use email/password auth.
 * - Both identities are bound into the family document at pairing time.
 */
object ParentalAuthManager {

    private const val TAG = "ParentalAuthManager"
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    /** Returns the currently signed-in user, or null. */
    fun getCurrentUser(): FirebaseUser? = auth.currentUser

    /** Returns the current user's UID, or null if not signed in. */
    fun getCurrentUid(): String? = auth.currentUser?.uid

    /** Returns true if there is a currently signed-in user. */
    fun isSignedIn(): Boolean = auth.currentUser != null

    // ── Child (anonymous) ───────────────────────────────────────────────

    /**
     * Signs in anonymously for a child device. If already signed in
     * anonymously, returns the existing user.
     */
    suspend fun signInAnonymously(): Result<FirebaseUser> {
        return try {
            val existing = auth.currentUser
            if (existing != null && existing.isAnonymous) {
                Log.i(TAG, "Already signed in anonymously")
                Result.success(existing)
            } else {
                val result = auth.signInAnonymously().await()
                val user = result.user
                    ?: return Result.failure(Exception("Anonymous sign-in returned null user"))
                Log.i(TAG, "Signed in anonymously")
                Result.success(user)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed", e)
            Result.failure(e)
        }
    }

    // ── Parent (email/password) ─────────────────────────────────────────

    /**
     * Creates a new parent account with email and password.
     */
    suspend fun createAccount(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user
                ?: return Result.failure(Exception("Account creation returned null user"))
            Log.i(TAG, "Created parent account")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Account creation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Signs in an existing parent with email and password.
     */
    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user
                ?: return Result.failure(Exception("Email sign-in returned null user"))
            Log.i(TAG, "Signed in parent")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Email sign-in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sends a password-reset email for the given address. Result is intentionally the same
     * shape whether or not an account exists for that email (Firebase's own behavior) — callers
     * should show the same neutral confirmation either way, to avoid account enumeration.
     */
    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email).await()
            Log.i(TAG, "Password reset email sent")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset email failed", e)
            Result.failure(e)
        }
    }

    /**
     * Signs out the current user.
     */
    fun signOut() {
        auth.signOut()
        Log.i(TAG, "Signed out")
    }

    /**
     * Re-authenticates the current parent with their password. Firebase requires a "recent
     * login" for sensitive operations like [deleteCurrentUser] — without this, a session that's
     * been open for a while fails deletion with FirebaseAuthRecentLoginRequiredException instead
     * of actually deleting anything.
     */
    suspend fun reauthenticateWithPassword(password: String): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("Not signed in"))
            val email = user.email ?: return Result.failure(Exception("No email on this account"))
            val credential = EmailAuthProvider.getCredential(email, password)
            user.reauthenticate(credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Re-authentication failed", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes the current Firebase Auth user outright. Callers are responsible for deleting any
     * associated Firestore data (e.g. via [PairingManager.unpair]) BEFORE calling this — deleting
     * the Auth identity first would leave that data orphaned with no owner able to reach it
     * (Firestore rules key everything off request.auth.uid, which stops existing the moment this
     * succeeds).
     */
    suspend fun deleteCurrentUser(): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("Not signed in"))
            user.delete().await()
            Log.i(TAG, "Deleted current user account")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Account deletion failed", e)
            Result.failure(e)
        }
    }
}
