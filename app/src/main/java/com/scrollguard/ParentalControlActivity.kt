package com.scrollguard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.scrollguard.data.ScrollGuardDatabase
import com.scrollguard.data.parental.ParentalAppRestriction
import com.scrollguard.data.parental.ParentalConfig
import com.scrollguard.databinding.ActivityParentalControlBinding
import com.scrollguard.parental.PairingManager
import com.scrollguard.parental.ParentalAppAdapter
import com.scrollguard.parental.ParentalAuthManager
import com.scrollguard.parental.SyncEngine
import com.scrollguard.parental.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ParentalControlActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ParentalControlActivity"
        private const val KEY_PENDING_PAIRING_CODE = "pending_pairing_code"

        /** A child not seen more recently than this is shown as "Last seen …", not "Connected". */
        private const val STALE_THRESHOLD_MS = 10 * 60 * 1000L

        /** Coalesces rapid +/- taps on the same package into a single network write. */
        private const val ALLOWANCE_WRITE_DEBOUNCE_MS = 400L
    }

    private lateinit var binding: ActivityParentalControlBinding
    private val db by lazy { ScrollGuardDatabase.getDatabase(this) }
    private val parentalDao by lazy { db.parentalDao() }
    private val syncEngine by lazy { SyncEngine(this) }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private var appAdapter: ParentalAppAdapter? = null
    private var pairingListener: ListenerRegistration? = null
    private var dashboardConfigListener: ListenerRegistration? = null
    private var dashboardAppsListener: ListenerRegistration? = null
    private var dashboardStatusListener: ListenerRegistration? = null
    private var dashboardRequestsListener: ListenerRegistration? = null

    private var currentConfig: ParentalConfig? = null
    private var currentConsumedMap: Map<String, Long> = emptyMap()

    // Guards setupAsChild() against a double-tap racing two concurrent signInAnonymously()
    // calls: FirebaseAuth's check-then-act on currentUser isn't synchronized across callers, so
    // two overlapping calls from a signed-out state can each mint a distinct anonymous UID plus
    // an orphaned family doc.
    private var roleSetupInFlight = false

    // Extracted so it can be detached/reattached when a failed write needs to revert the
    // switch's visual state without re-triggering itself (same pattern as MainActivity's
    // strictModeListener). Declared lateinit + assigned in setupUI() rather than initialized
    // here directly, since the listener body needs to reference this same property to
    // reattach itself (a val initializer can't reference itself).
    private lateinit var globalRestrictionsListener: android.widget.CompoundButton.OnCheckedChangeListener

    private fun buildGlobalRestrictionsListener(): android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
        val fid = currentConfig?.familyId
        if (fid == null) return@OnCheckedChangeListener
        lifecycleScope.launch(Dispatchers.IO) {
            val result = syncEngine.writeGlobalEnabled(fid, isChecked)
            withContext(Dispatchers.Main) {
                if (result.isFailure) {
                    Toast.makeText(
                        this@ParentalControlActivity,
                        getString(R.string.error_change_not_saved, friendlyErrorMessage(result.exceptionOrNull())),
                        Toast.LENGTH_LONG
                    ).show()
                    // The write never landed, so the child device never received this change —
                    // revert the switch to the state it was in before this tap.
                    binding.switchGlobalRestrictions.setOnCheckedChangeListener(null)
                    binding.switchGlobalRestrictions.isChecked = !isChecked
                    binding.switchGlobalRestrictions.setOnCheckedChangeListener(globalRestrictionsListener)
                }
            }
        }
    }

    // The pairing code is only ever known transiently (never persisted to Room — it's a
    // Firestore-side ephemeral secret, not device config) so it must ride out Activity
    // recreation via saved-instance state, or a rotation while the child's code/QR screen is
    // showing loses it permanently with no way to recover it short of clearing app data.
    private var pendingPairingCode: String? = null

    // Per-package in-flight allowance-write jobs, so a burst of +/- taps on the same app
    // debounces into one network write of the LATEST value instead of N racing writes that can
    // resolve out of order.
    private val pendingAllowanceWrites = mutableMapOf<String, Job>()

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scannedCode = result.contents.trim().uppercase()
            binding.etPairingCode.setText(scannedCode)
            performClaimPairingCode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        pendingPairingCode = savedInstanceState?.getString(KEY_PENDING_PAIRING_CODE)

        setupUI()
        loadInitialState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPairingCode?.let { outState.putString(KEY_PENDING_PAIRING_CODE, it) }
    }

    override fun onDestroy() {
        pairingListener?.remove()
        dashboardConfigListener?.remove()
        dashboardAppsListener?.remove()
        dashboardStatusListener?.remove()
        dashboardRequestsListener?.remove()
        super.onDestroy()
    }

    private fun setupUI() {
        // Role selection
        binding.cardChild.setOnClickListener { setupAsChild() }
        binding.cardParent.setOnClickListener { setupAsParent() }

        // Parent Auth
        binding.btnSignIn.setOnClickListener { performParentSignIn() }
        binding.btnCreateAccount.setOnClickListener { performParentCreateAccount() }
        binding.btnForgotPassword.setOnClickListener { performPasswordReset() }

        // Parent Claim Code & QR Scan
        binding.btnClaimCode.setOnClickListener { performClaimPairingCode() }
        binding.btnScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.scan_qr_code))
                setCameraId(0)
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
                setOrientationLocked(true)
            }
            qrScanLauncher.launch(options)
        }

        // Parent Dashboard
        binding.rvRestrictedApps.layoutManager = LinearLayoutManager(this)
        appAdapter = ParentalAppAdapter(
            items = emptyList(),
            onAllowanceChanged = { app, newAllowance ->
                val fid = currentConfig?.familyId ?: return@ParentalAppAdapter
                // Cancel any not-yet-fired write for this package and replace it — a burst of
                // rapid taps (each already carrying the correctly-stacked value from the
                // adapter's own optimistic update) should persist only the LATEST one, not fire
                // N overlapping writes that could resolve out of order and leave a stale value.
                pendingAllowanceWrites[app.packageName]?.cancel()
                pendingAllowanceWrites[app.packageName] = lifecycleScope.launch(Dispatchers.IO) {
                    delay(ALLOWANCE_WRITE_DEBOUNCE_MS)
                    val result = syncEngine.writeAppRestriction(
                        fid, app.packageName, app.appName, app.enabled, newAllowance
                    )
                    withContext(Dispatchers.Main) { handleSyncWriteResult(result, fid) }
                }
            },
            onEnabledChanged = { app, newEnabled ->
                val fid = currentConfig?.familyId ?: return@ParentalAppAdapter
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = syncEngine.writeAppRestriction(
                        fid, app.packageName, app.appName, newEnabled, app.allowanceSeconds
                    )
                    withContext(Dispatchers.Main) { handleSyncWriteResult(result, fid) }
                }
            },
            onDeleteClicked = { app ->
                val fid = currentConfig?.familyId ?: return@ParentalAppAdapter
                // Destructive action — confirm first, matching confirmDeleteGroup()/
                // confirmUnpair()'s existing pattern elsewhere in the app, rather than removing
                // a child's restriction on a single mis-tap.
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_restriction_confirm, app.appName))
                    .setPositiveButton(R.string.cd_remove_restriction) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val result = syncEngine.removeAppRestriction(fid, app.packageName)
                            withContext(Dispatchers.Main) { handleSyncWriteResult(result, fid) }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )
        binding.rvRestrictedApps.adapter = appAdapter

        globalRestrictionsListener = buildGlobalRestrictionsListener()
        binding.switchGlobalRestrictions.setOnCheckedChangeListener(globalRestrictionsListener)

        binding.btnManageApps.setOnClickListener {
            val fid = currentConfig?.familyId ?: return@setOnClickListener
            val intent = Intent(this, ParentalAppPickerActivity::class.java).apply {
                putExtra(ParentalAppPickerActivity.EXTRA_FAMILY_ID, fid)
            }
            startActivity(intent)
        }

        binding.btnUnpair.setOnClickListener { confirmUnpair() }
        binding.btnChildUnpair.setOnClickListener { confirmUnpair() }
    }

    private fun loadInitialState() {
        showLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val config = parentalDao.getConfig()
            currentConfig = config

            withContext(Dispatchers.Main) {
                showLoading(false)
                if (config == null || config.role == null) {
                    showRoleSelection()
                } else if (config.role == "child") {
                    if (config.isPaired) {
                        // The parent branch below re-validates against the live auth session
                        // before trusting cached "paired" state; the child branch must too — if
                        // this device's anonymous session was ever lost or invalidated after
                        // pairing, syncOnAppOpen() would just fail silently forever (Firestore
                        // permission-denied) while this screen kept claiming "paired", making a
                        // broken pairing indistinguishable from a merely-offline one.
                        if (ParentalAuthManager.isSignedIn()) {
                            showChildStatus(config)
                            // Sync-on-app-open fallback (spec Part B Issue C) — without this, a
                            // returning child only ever picks up a parent's change via the
                            // 15-min SyncWorker floor (there's no FCM push implemented yet either).
                            syncOnAppOpen()
                        } else {
                            showChildNeedsRepair()
                        }
                    } else if (config.familyId != null) {
                        // Resuming waiting for pair — restore whatever code survived rotation
                        // via onSaveInstanceState (Room never stores the raw code itself).
                        showChildPairingView(config.familyId, pendingPairingCode)
                    } else {
                        showRoleSelection()
                    }
                } else if (config.role == "parent") {
                    if (ParentalAuthManager.isSignedIn()) {
                        if (config.isPaired && config.familyId != null) {
                            showParentDashboard(config.familyId)
                        } else {
                            showParentPairingView()
                        }
                    } else {
                        showParentAuthView()
                    }
                }
            }
        }
    }

    /**
     * Re-syncs config/status on app open for an already-paired child. This is the fallback the
     * spec requires alongside the 15-min SyncWorker floor and (not yet built) FCM push — without
     * it, a child that never happens to have the periodic worker fire while the app is open has
     * no other way to see a parent's change land. Safe to call opportunistically: pullConfig/
     * pushStatus already fail closed (Result.failure) and never touch Room/ParentalControlState
     * on failure, and this coroutine is cancelled automatically if the Activity is destroyed
     * before it completes.
     */
    private fun syncOnAppOpen() {
        lifecycleScope.launch(Dispatchers.IO) {
            syncEngine.pullConfig()
            syncEngine.pushStatus()
            val updated = parentalDao.getConfig() ?: return@launch
            currentConfig = updated
            withContext(Dispatchers.Main) {
                showChildStatus(updated)
            }
        }
    }

    // ── Visibility Helpers ──────────────────────────────────────────────

    private fun hideAllViews() {
        binding.layoutRoleSelection.visibility = View.GONE
        binding.layoutChildPairing.visibility = View.GONE
        binding.layoutParentAuth.visibility = View.GONE
        binding.layoutParentPairing.visibility = View.GONE
        binding.layoutParentDashboard.visibility = View.GONE
        binding.layoutChildStatus.visibility = View.GONE
    }

    private fun showLoading(loading: Boolean) {
        binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showRoleSelection() {
        hideAllViews()
        binding.layoutRoleSelection.visibility = View.VISIBLE
    }

    // ── Child Flow ──────────────────────────────────────────────────────

    private fun setupAsChild() {
        if (roleSetupInFlight) return
        roleSetupInFlight = true
        showLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val authResult = ParentalAuthManager.signInAnonymously()
                val user = authResult.getOrNull()
                if (user == null) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        Toast.makeText(
                            this@ParentalControlActivity,
                            getString(R.string.error_auth_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val pairResult = PairingManager.generatePairingCode(user.uid)
                val (familyId, code) = pairResult.getOrNull() ?: run {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        Toast.makeText(
                            this@ParentalControlActivity,
                            getString(R.string.error_generate_code_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val initialConfig = ParentalConfig(
                    role = "child",
                    familyId = familyId,
                    childUid = user.uid,
                    isPaired = false
                )
                parentalDao.upsertConfig(initialConfig)
                currentConfig = initialConfig
                pendingPairingCode = code

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showChildPairingView(familyId, code)
                }
            } finally {
                roleSetupInFlight = false
            }
        }
    }

    private fun showChildPairingView(familyId: String, code: String? = null) {
        hideAllViews()
        binding.layoutChildPairing.visibility = View.VISIBLE
        if (code != null) {
            binding.tvPairingCode.text = code
            val qr = generateQrBitmap(code)
            if (qr != null) {
                binding.ivQrCode.setImageBitmap(qr)
            }
        } else {
            binding.tvPairingCode.text = "..."
        }

        // Listen for parent to claim the code
        pairingListener?.remove()
        pairingListener = firestore.collection("families").document(familyId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val parentUid = snapshot.getString("parentUid")
                if (!parentUid.isNullOrEmpty()) {
                    // Paired!
                    pairingListener?.remove()
                    onChildPairedSuccessfully(familyId, parentUid)
                }
            }
    }

    private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode QR", e)
            null
        }
    }

    private fun onChildPairedSuccessfully(familyId: String, parentUid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = currentConfig?.copy(
                isPaired = true,
                parentUid = parentUid
            ) ?: ParentalConfig(
                role = "child",
                familyId = familyId,
                parentUid = parentUid,
                isPaired = true
            )
            parentalDao.upsertConfig(updated)
            currentConfig = updated
            pendingPairingCode = null

            // Hydrate state & schedule periodic sync
            ParentalControlState.hydrateFromRoom(applicationContext, parentalDao)
            SyncWorker.schedule(applicationContext)

            // Push catalog & pull config
            val catalog = buildAppCatalog()
            syncEngine.pushCatalog(catalog)
            syncEngine.pullConfig()

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ParentalControlActivity,
                    getString(R.string.child_paired_success),
                    Toast.LENGTH_LONG
                ).show()
                showChildStatus(updated)
            }
        }
    }

    private fun showChildStatus(config: ParentalConfig) {
        hideAllViews()
        binding.layoutChildStatus.visibility = View.VISIBLE
        binding.tvChildSyncStatus.text = if (config.globalEnabled) {
            getString(R.string.restrictions_enabled)
        } else {
            getString(R.string.restrictions_disabled)
        }
    }

    /**
     * Shown instead of the normal child status when Room still says "paired" but this device's
     * Firebase session is gone — reuses the same screen and its existing btnChildUnpair (already
     * wired to confirmUnpair()/performUnpair(), which cleanly clears local state regardless of
     * whether the now-invalid session can still reach Firestore) rather than a distinct flow.
     */
    private fun showChildNeedsRepair() {
        hideAllViews()
        binding.layoutChildStatus.visibility = View.VISIBLE
        binding.tvChildSyncStatus.text = getString(R.string.child_needs_repair)
    }

    private fun buildAppCatalog(): List<Map<String, String>> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != packageName && seen.add(it.packageName) }
            .map { appInfo ->
                mapOf(
                    "packageName" to appInfo.packageName,
                    "label" to pm.getApplicationLabel(appInfo).toString()
                )
            }
    }

    // ── Parent Flow ─────────────────────────────────────────────────────

    private fun setupAsParent() {
        if (ParentalAuthManager.isSignedIn()) {
            showParentPairingView()
        } else {
            showParentAuthView()
        }
    }

    private fun showParentAuthView() {
        hideAllViews()
        binding.layoutParentAuth.visibility = View.VISIBLE
    }

    private fun performParentSignIn() {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString()?.trim() ?: ""

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_fill_fields), Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressAuth.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val result = ParentalAuthManager.signInWithEmail(email, password)
            withContext(Dispatchers.Main) {
                binding.progressAuth.visibility = View.GONE
                if (result.isSuccess) {
                    showParentPairingView()
                } else {
                    Toast.makeText(
                        this@ParentalControlActivity,
                        friendlyErrorMessage(result.exceptionOrNull()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun performParentCreateAccount() {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString()?.trim() ?: ""

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_fill_fields), Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, getString(R.string.error_password_short), Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressAuth.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val result = ParentalAuthManager.createAccount(email, password)
            withContext(Dispatchers.Main) {
                binding.progressAuth.visibility = View.GONE
                if (result.isSuccess) {
                    showParentPairingView()
                } else {
                    Toast.makeText(
                        this@ParentalControlActivity,
                        friendlyErrorMessage(result.exceptionOrNull()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Maps a raw Firebase/network exception to a plain-language message — the previous code
     * showed `exceptionOrNull()?.localizedMessage` directly, which can surface raw SDK/internal
     * error text a non-technical user can't act on.
     */
    private fun friendlyErrorMessage(e: Throwable?): String = when (e) {
        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException,
        is com.google.firebase.auth.FirebaseAuthInvalidUserException ->
            getString(R.string.error_invalid_credentials)
        is com.google.firebase.auth.FirebaseAuthUserCollisionException ->
            getString(R.string.error_account_exists)
        is com.google.firebase.auth.FirebaseAuthWeakPasswordException ->
            getString(R.string.error_password_short)
        is com.google.firebase.firestore.FirebaseFirestoreException ->
            when (e.code) {
                com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE ->
                    getString(R.string.error_no_connection)
                com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    getString(R.string.error_permission_denied)
                else -> getString(R.string.error_generic_retry)
            }
        is java.io.IOException -> getString(R.string.error_no_connection)
        else -> getString(R.string.error_generic_retry)
    }

    /**
     * Pairing-code claim failures come from PairingManager as plain `Exception(message)` with a
     * fixed set of internal English literals (not user-facing strings) — map the ones we
     * recognize to the existing localized error strings instead of showing raw exception text.
     */
    private fun mapClaimError(e: Throwable?): String = when (e?.message) {
        "Pairing code already used" -> getString(R.string.error_code_used)
        "Pairing code expired" -> getString(R.string.error_code_expired)
        "Invalid pairing code", "Pairing code has no family reference" -> getString(R.string.error_invalid_code)
        else -> friendlyErrorMessage(e)
    }

    private fun performPasswordReset() {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        if (email.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_fill_fields), Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressAuth.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            // Result intentionally unused for branching: the same neutral message is shown
            // whether or not an account exists for this email, to avoid account enumeration.
            ParentalAuthManager.sendPasswordResetEmail(email)
            withContext(Dispatchers.Main) {
                binding.progressAuth.visibility = View.GONE
                Toast.makeText(this@ParentalControlActivity, getString(R.string.password_reset_sent), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showParentPairingView() {
        hideAllViews()
        binding.layoutParentPairing.visibility = View.VISIBLE
    }

    private fun performClaimPairingCode() {
        val code = binding.etPairingCode.text?.toString()?.trim()?.uppercase() ?: ""
        if (code.length != 6) {
            Toast.makeText(this, getString(R.string.error_invalid_code), Toast.LENGTH_SHORT).show()
            return
        }

        val parentUid = ParentalAuthManager.getCurrentUid() ?: return
        binding.progressClaim.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val claimResult = PairingManager.claimPairingCode(code, parentUid)
            val familyId = claimResult.getOrNull()

            withContext(Dispatchers.Main) {
                binding.progressClaim.visibility = View.GONE
                if (familyId != null) {
                    val config = ParentalConfig(
                        role = "parent",
                        familyId = familyId,
                        parentUid = parentUid,
                        isPaired = true
                    )
                    lifecycleScope.launch(Dispatchers.IO) {
                        parentalDao.upsertConfig(config)
                        currentConfig = config
                    }
                    showParentDashboard(familyId)
                } else {
                    Toast.makeText(
                        this@ParentalControlActivity,
                        mapClaimError(claimResult.exceptionOrNull()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ── Parent Dashboard ────────────────────────────────────────────────

    private fun showParentDashboard(familyId: String) {
        hideAllViews()
        binding.layoutParentDashboard.visibility = View.VISIBLE
        // Neutral state until the listeners below return real data — the layout's static
        // default text is "Connected", which would otherwise be misleading (spec: "no
        // misleading connection status") for however long the first read takes, especially
        // if the parent itself is offline when opening the dashboard.
        binding.tvChildStatus.text = getString(R.string.child_status_loading)

        // Listen for child family doc updates (device name, etc.)
        val familyRef = firestore.collection("families").document(familyId)
        familyRef.get()
            .addOnSuccessListener { snapshot ->
                val deviceName = snapshot.getString("childDeviceName") ?: getString(R.string.child_device)
                binding.tvChildDeviceName.text = deviceName
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to read family document", e)
                Toast.makeText(this, getString(R.string.error_sync_failed), Toast.LENGTH_SHORT).show()
            }

        // Listen for config changes (global enabled, etc.)
        dashboardConfigListener?.remove()
        dashboardConfigListener = familyRef.collection("config").document("current")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val enabled = snapshot.getBoolean("enabled") ?: false
                binding.switchGlobalRestrictions.setOnCheckedChangeListener(null)
                binding.switchGlobalRestrictions.isChecked = enabled
                binding.switchGlobalRestrictions.setOnCheckedChangeListener { _, isChecked ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        syncEngine.writeGlobalEnabled(familyId, isChecked)
                    }
                }
            }

        // Listen for status changes (consumed time per package)
        dashboardStatusListener?.remove()
        dashboardStatusListener = familyRef.collection("status").document("current")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                updateChildConnectionStatus(snapshot.getTimestamp("lastSeen"))

                @Suppress("UNCHECKED_CAST")
                val consumedByPkg = snapshot.get("consumedByPackage") as? Map<String, Map<String, Any>>
                val consumedMap = mutableMapOf<String, Long>()
                consumedByPkg?.forEach { (pkg, data) ->
                    val sec = (data["consumedSeconds"] as? Number)?.toLong() ?: 0L
                    consumedMap[pkg] = sec
                }
                currentConsumedMap = consumedMap
                refreshParentAppList(familyId)
            }

        // Listen for restricted apps list
        dashboardAppsListener?.remove()
        dashboardAppsListener = familyRef.collection("config").document("current")
            .collection("apps")
            .addSnapshotListener { _, _ ->
                refreshParentAppList(familyId)
            }

        // Listen for pending time requests
        dashboardRequestsListener?.remove()
        dashboardRequestsListener = familyRef.collection("requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, _ ->
                val requestDoc = snapshot?.documents?.firstOrNull()
                if (requestDoc != null) {
                    val pkg = requestDoc.getString("packageName") ?: ""
                    val appName = requestDoc.getString("appName") ?: pkg
                    val mins = requestDoc.getLong("minutes") ?: 10L
                    val childName = binding.tvChildDeviceName.text.toString()

                    binding.cardPendingRequests.visibility = View.VISIBLE
                    binding.tvRequestDetails.text = getString(
                        R.string.child_requested_time_format, childName, mins, appName
                    )
                    binding.btnApproveRequest.isEnabled = true
                    binding.btnDenyRequest.isEnabled = true

                    binding.btnApproveRequest.setOnClickListener {
                        binding.btnApproveRequest.isEnabled = false
                        binding.btnDenyRequest.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                // 1. Mark request APPROVED
                                requestDoc.reference.update("status", "APPROVED").await()
                                // 2. Increase allowance by mins * 60. Read the CURRENT allowance the
                                // same way the +/- stepper does (a fresh Firestore read) —
                                // ParentalControlState is only ever populated via refreshFromSync,
                                // which is only ever called on a CHILD-role device, so on the parent
                                // device reading it here was always null and silently fell back to
                                // a hardcoded 3600s — meaning "approve" OVERWROTE the child's real
                                // allowance instead of extending it.
                                val appDoc = familyRef.collection("config").document("current")
                                    .collection("apps").document(pkg).get().await()
                                val currentAllowance = appDoc.getLong("allowanceSeconds")?.toInt() ?: 3600
                                val newAllowance = (currentAllowance + (mins.toInt() * 60)).coerceAtMost(24 * 3600)
                                val result = syncEngine.writeAppRestriction(familyId, pkg, appName, true, newAllowance)
                                if (result.isFailure) throw result.exceptionOrNull() ?: Exception("write failed")
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@ParentalControlActivity,
                                        getString(R.string.error_change_not_saved, friendlyErrorMessage(e)),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    binding.btnApproveRequest.isEnabled = true
                                    binding.btnDenyRequest.isEnabled = true
                                }
                            }
                        }
                    }

                    binding.btnDenyRequest.setOnClickListener {
                        binding.btnApproveRequest.isEnabled = false
                        binding.btnDenyRequest.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                requestDoc.reference.update("status", "DENIED").await()
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@ParentalControlActivity,
                                        getString(R.string.error_change_not_saved, friendlyErrorMessage(e)),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    binding.btnApproveRequest.isEnabled = true
                                    binding.btnDenyRequest.isEnabled = true
                                }
                            }
                        }
                    }
                } else {
                    binding.cardPendingRequests.visibility = View.GONE
                }
            }
    }

    /**
     * Renders the child's connection status from an actual lastSeen timestamp rather than
     * trusting the status doc's syncState field — the child currently always writes
     * syncState="SYNCED" regardless of its real connectivity (a separate, deferred gap — see
     * AUDIT_PROGRESS.md), so syncState alone can't distinguish stale from fresh. A child not
     * seen within STALE_THRESHOLD_MS is shown as "Last seen …", never as "Connected", so a
     * long-disconnected child can't misleadingly read as currently reachable.
     */
    private fun updateChildConnectionStatus(lastSeen: com.google.firebase.Timestamp?) {
        binding.tvChildStatus.text = when {
            lastSeen == null -> getString(R.string.child_offline)
            System.currentTimeMillis() - lastSeen.toDate().time <= STALE_THRESHOLD_MS ->
                getString(R.string.child_connected)
            else -> getString(
                R.string.child_last_seen_format,
                java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(lastSeen.toDate())
            )
        }
    }

    /**
     * Common handling for a dashboard app-list write's Result: on failure, tell the parent
     * their change didn't save and re-fetch the true server state to correct whatever the
     * adapter's optimistic update now shows — otherwise a failed write silently leaves the UI
     * out of sync with what the child device will actually enforce.
     */
    private fun handleSyncWriteResult(result: Result<Unit>, familyId: String) {
        if (result.isFailure) {
            Toast.makeText(
                this,
                getString(R.string.error_change_not_saved, friendlyErrorMessage(result.exceptionOrNull())),
                Toast.LENGTH_LONG
            ).show()
            refreshParentAppList(familyId)
        }
    }

    private fun refreshParentAppList(familyId: String) {
        val familyRef = firestore.collection("families").document(familyId)
        familyRef.collection("config").document("current")
            .collection("apps").get().addOnSuccessListener { snapshot ->
                val list = snapshot.documents.map { doc ->
                    val pkg = doc.id
                    val label = doc.getString("label") ?: pkg
                    val enabled = doc.getBoolean("enabled") ?: true
                    val allowance = doc.getLong("allowanceSeconds")?.toInt() ?: 3600
                    val consumed = currentConsumedMap[pkg] ?: 0L

                    ParentalAppRestriction(
                        packageName = pkg,
                        appName = label,
                        enabled = enabled,
                        allowanceSeconds = allowance,
                        consumedSeconds = consumed,
                        consumedEpochDay = 0L
                    )
                }

                appAdapter?.submitList(list)
                binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    // ── Unpair ──────────────────────────────────────────────────────────

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.unpair))
            .setMessage(getString(R.string.unpair_confirm))
            .setPositiveButton(getString(R.string.unpair)) { _, _ ->
                performUnpair()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performUnpair() {
        val fid = currentConfig?.familyId
        showLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            if (fid != null) {
                PairingManager.unpair(fid)
            }
            parentalDao.clearConfig()
            parentalDao.clearAllRestrictions()
            ParentalControlState.clear()
            SyncWorker.cancel(applicationContext)
            ParentalAuthManager.signOut()
            currentConfig = null

            withContext(Dispatchers.Main) {
                showLoading(false)
                Toast.makeText(this@ParentalControlActivity, getString(R.string.unpaired_success), Toast.LENGTH_SHORT).show()
                showRoleSelection()
            }
        }
    }
}
