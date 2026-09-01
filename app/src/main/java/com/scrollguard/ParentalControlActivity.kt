package com.scrollguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.scrollguard.parental.FamilyHubAdapter
import com.scrollguard.parental.FamilyHubItem
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
    private var familyHubAdapter: FamilyHubAdapter? = null
    private var pairingListener: ListenerRegistration? = null
    private var dashboardConfigListener: ListenerRegistration? = null
    private var dashboardAppsListener: ListenerRegistration? = null
    private var dashboardStatusListener: ListenerRegistration? = null
    private var dashboardRequestsListener: ListenerRegistration? = null
    private var unpairRequestListener: ListenerRegistration? = null

    // True only on the "connection lost" child screen (Room says paired but the anonymous
    // Firebase session is gone) — that path can't reach Firestore to file an unpair request, so
    // its Unpair button stays the old immediate local-only recovery action. The normal paired
    // child screen instead requires parent approval (see confirmChildUnpairAction()).
    private var childSessionNeedsRepair = false

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
        setGlobalRestrictionsChecked(isChecked)
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
                    setGlobalRestrictionsChecked(!isChecked)
                }
            }
        }
    }

    /**
     * The switch's on-screen label ("Restrictions ON"/"Restrictions OFF") was hardcoded in the
     * layout and never actually updated — only isChecked was ever touched, so a parent could see
     * the switch visually off while the label still read "Restrictions ON". This is the single
     * place that changes isChecked, so the label can never drift from it again.
     */
    private fun setGlobalRestrictionsChecked(enabled: Boolean) {
        binding.switchGlobalRestrictions.setOnCheckedChangeListener(null)
        binding.switchGlobalRestrictions.isChecked = enabled
        binding.switchGlobalRestrictions.text = getString(
            if (enabled) R.string.restrictions_enabled else R.string.restrictions_disabled
        )
        binding.switchGlobalRestrictions.setOnCheckedChangeListener(globalRestrictionsListener)
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
        // No else branch: by the time this launches, CAMERA permission has already been
        // explicitly checked/requested by us (see launchQrScanner()), so a null result here
        // just means the user backed out of the scanner — not a permission denial silently
        // masquerading as one, which was the previous ambiguity.
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScanner()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            if (binding.layoutParentDashboard.visibility == View.VISIBLE && currentConfig?.role == "parent") {
                showFamilyHub()
            } else if (binding.layoutParentPairing.visibility == View.VISIBLE && currentConfig?.role == "parent") {
                // If they are paired but in pairing view, go back to Hub
                if (currentConfig?.isPaired == true) showFamilyHub() else finish()
            } else {
                finish()
            }
        }

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
        unpairRequestListener?.remove()
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
        binding.etPairingCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performClaimPairingCode()
                true
            } else {
                false
            }
        }
        binding.btnScanQr.setOnClickListener { requestCameraThenScan() }

        // Family Hub
        binding.rvFamilyHub.layoutManager = LinearLayoutManager(this)
        familyHubAdapter = FamilyHubAdapter(
            onItemClick = { item ->
                showParentDashboard(item.familyId)
            },
            onEditClick = { item ->
                promptRenameChild(item)
            }
        )
        binding.rvFamilyHub.adapter = familyHubAdapter
        
        binding.btnPairAnotherChild.setOnClickListener { showParentPairingView() }
        binding.btnFamilyHubSignOut.setOnClickListener { confirmDeleteAccount() }

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
        // Syncs the label to the switch's actual (default, unchecked) state up front — the
        // layout's static text otherwise reads "Restrictions ON" for the brief window before
        // the dashboard's first Firestore snapshot arrives, while the switch itself defaults
        // to unchecked.
        setGlobalRestrictionsChecked(binding.switchGlobalRestrictions.isChecked)

        binding.btnManageApps.setOnClickListener {
            val fid = currentConfig?.familyId ?: return@setOnClickListener
            val intent = Intent(this, ParentalAppPickerActivity::class.java).apply {
                putExtra(ParentalAppPickerActivity.EXTRA_FAMILY_ID, fid)
            }
            startActivity(intent)
        }

        binding.btnUnpair.setOnClickListener { confirmUnpair() }
        binding.btnDeleteAccountFromDashboard.setOnClickListener { confirmDeleteAccount() }
        binding.btnDeleteAccountFromPairing.setOnClickListener { confirmDeleteAccount() }
        // A paired child with a live session must ask the parent (see confirmChildUnpairAction);
        // the "connection lost" repair screen reuses this same button for its old immediate,
        // local-only recovery action, since that screen has no working Firestore session to
        // file a request through in the first place.
        binding.btnChildUnpair.setOnClickListener { confirmChildUnpairAction() }
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
                        // Same fix as the isPaired branch above, and for the same reason: this
                        // screen's Firestore reads/writes (regenerating a code, listening for
                        // the parent to claim it) all require a live, signed-in session. Found
                        // live while testing the "Get a New Code" recovery path below — without
                        // this check, a device whose anonymous session had been lost showed the
                        // pairing UI anyway and every Firestore call on it failed with
                        // PERMISSION_DENIED, with the regenerate button just silently staying
                        // disabled forever (no error surfaced) instead of explaining what's
                        // actually wrong.
                        if (ParentalAuthManager.isSignedIn()) {
                            // Resuming waiting for pair — restore whatever code survived
                            // rotation via onSaveInstanceState (Room never stores the raw code).
                            showChildPairingView(config.familyId, pendingPairingCode)
                        } else {
                            showChildNeedsRepair()
                        }
                    } else {
                        showRoleSelection()
                    }
                } else if (config.role == "parent") {
                    if (ParentalAuthManager.isSignedIn()) {
                        if (config.isPaired) {
                            showFamilyHub()
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
        binding.layoutFamilyHub.visibility = View.GONE
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
            binding.cardQrCode.visibility = View.VISIBLE
            binding.tvQrHint.visibility = View.VISIBLE
            binding.tvPairingCode.visibility = View.VISIBLE
            binding.tvPairingStatus.visibility = View.VISIBLE
            binding.btnRegenerateCode.visibility = View.GONE
            binding.tvPairingCode.text = code
            val qr = generateQrBitmap(code)
            if (qr != null) {
                binding.ivQrCode.setImageBitmap(qr)
            }
            listenForPairing(familyId)
        } else {
            // No code survived (e.g. the child left and returned to this screen before the
            // parent claimed it) — there's nothing to show or listen for until a new one is
            // generated, so offer that directly instead of a permanently frozen "...".
            binding.cardQrCode.visibility = View.GONE
            binding.tvQrHint.visibility = View.GONE
            binding.tvPairingCode.visibility = View.GONE
            binding.tvPairingStatus.visibility = View.GONE
            binding.btnRegenerateCode.visibility = View.VISIBLE
            binding.btnRegenerateCode.setOnClickListener {
                binding.btnRegenerateCode.isEnabled = false
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = PairingManager.regeneratePairingCode(familyId)
                    withContext(Dispatchers.Main) {
                        binding.btnRegenerateCode.isEnabled = true
                        val newCode = result.getOrNull()
                        if (newCode != null) {
                            pendingPairingCode = newCode
                            showChildPairingView(familyId, newCode)
                        } else {
                            Toast.makeText(
                                this@ParentalControlActivity,
                                getString(R.string.error_generate_code_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun listenForPairing(familyId: String) {
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
        childSessionNeedsRepair = false
        binding.btnChildUnpair.isEnabled = true
        binding.btnChildUnpair.text = getString(R.string.request_unpair)
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
        childSessionNeedsRepair = true
        binding.btnChildUnpair.isEnabled = true
        binding.btnChildUnpair.text = getString(R.string.unpair)
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
                    val uid = ParentalAuthManager.getCurrentUid()
                    if (uid != null) {
                        // We sign in as a parent, assume paired if we reach here
                        val config = ParentalConfig(role = "parent", isPaired = true, parentUid = uid)
                        lifecycleScope.launch(Dispatchers.IO) { parentalDao.upsertConfig(config) }
                        currentConfig = config
                    }
                    showFamilyHub()
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

    /**
     * Explains camera use before ever prompting for it — matching SetupGuideActivity's
     * "explain before asking" pattern elsewhere in the app — instead of the QR library's own
     * permission dialog appearing with zero ScrollGuard context. Skips straight to the scanner
     * if permission is already granted (only ever shown once per install otherwise).
     */
    private fun requestCameraThenScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchQrScanner()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.camera_permission_rationale_title)
                .setMessage(R.string.camera_permission_rationale_body)
                .setPositiveButton(R.string.setup_step_allow) { _, _ ->
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun launchQrScanner() {
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
                    // The child gets an explicit "Paired successfully!" toast on their side
                    // (onChildPairedSuccessfully) — the parent previously got no equivalent
                    // confirmation at all, just a silent switch to the dashboard.
                    Toast.makeText(this@ParentalControlActivity, getString(R.string.child_paired_success), Toast.LENGTH_LONG).show()
                    showFamilyHub()
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

    private fun promptRenameChild(item: FamilyHubItem) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(item.childDeviceName)
            setSelection(item.childDeviceName.length)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Device")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != item.childDeviceName) {
                    showLoading(true)
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            firestore.collection("families").document(item.familyId)
                                .update("childDeviceName", newName).await()
                            withContext(Dispatchers.Main) {
                                showLoading(false)
                                showFamilyHub()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                showLoading(false)
                                Toast.makeText(
                                    this@ParentalControlActivity,
                                    getString(R.string.error_change_not_saved, friendlyErrorMessage(e)),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFamilyHub() {
        hideAllViews()
        binding.layoutFamilyHub.visibility = View.VISIBLE
        showLoading(true)
        
        val uid = ParentalAuthManager.getCurrentUid() ?: return
        
        firestore.collection("families")
            .whereEqualTo("parentUid", uid)
            .get()
            .addOnSuccessListener { querySnapshot ->
                showLoading(false)
                val items = querySnapshot.documents.mapNotNull { doc ->
                    val fid = doc.id
                    val deviceName = doc.getString("childDeviceName") ?: getString(R.string.child_device)
                    FamilyHubItem(fid, deviceName)
                }
                
                if (items.isEmpty()) {
                    showParentPairingView()
                } else {
                    familyHubAdapter?.submitList(items)
                }
            }
            .addOnFailureListener {
                showLoading(false)
                Toast.makeText(this, getString(R.string.error_sync_failed), Toast.LENGTH_SHORT).show()
            }
    }

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
                // Reattaches the SAME listener buildGlobalRestrictionsListener() built (which
                // does handle write failures — reverts the switch and toasts an error) instead
                // of a bespoke inline lambda with none. This snapshot fires almost immediately
                // whenever the dashboard opens, so the bespoke lambda was overwriting the
                // error-handling listener on essentially every dashboard visit, making failed
                // toggle writes fail completely silently in practice.
                setGlobalRestrictionsChecked(enabled)
            }

        // Listen for status changes (consumed time per package)
        dashboardStatusListener?.remove()
        dashboardStatusListener = familyRef.collection("status").document("current")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val accessibilityHealthy = snapshot.getBoolean("accessibilityHealthy") ?: true
                updateChildConnectionStatus(
                    snapshot.getTimestamp("lastSeen"),
                    accessibilityHealthy,
                    snapshot.getString("lastTamperEvent"),
                    snapshot.getTimestamp("lastTamperEventAt")
                )

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
                // Missing "type" means an older/existing time-request doc predating this field.
                if (requestDoc != null && requestDoc.getString("type") == "UNPAIR") {
                    val childName = binding.tvChildDeviceName.text.toString()
                    binding.cardPendingRequests.visibility = View.VISIBLE
                    binding.tvRequestDetails.text = getString(R.string.child_requested_unpair_format, childName)
                    binding.btnApproveRequest.isEnabled = true
                    binding.btnDenyRequest.isEnabled = true

                    // Approving IS calling unpair() — there's no separate "mark approved" step.
                    // This deletes the family record (and this request doc with it), which is
                    // exactly what tells the child's own listener (sendUnpairRequest) it was
                    // approved. Reuses the parent's own unpair flow verbatim rather than a
                    // second, parallel implementation of the same cleanup.
                    binding.btnApproveRequest.setOnClickListener {
                        binding.btnApproveRequest.isEnabled = false
                        binding.btnDenyRequest.isEnabled = false
                        performUnpair()
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
                } else if (requestDoc != null) {
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
     * Renders the child's connection status from an actual lastSeen timestamp, computed
     * client-side, rather than trusting a status flag the child sets. A child not seen within
     * STALE_THRESHOLD_MS is shown as "Last seen …", never as "Connected", so a long-disconnected
     * child can't misleadingly read as currently reachable.
     *
     * [accessibilityHealthy] mirrors TimerState.accessibilityHealthy from the child device (see
     * SyncEngine.pushStatus) — surfaced here so a parent can tell the enforcement mechanism
     * itself was disabled, distinct from the device simply being offline.
     *
     * [tamperEvent]/[tamperEventAt] (see SyncEngine.pushTamperAlert) report a specific event —
     * e.g. Device Admin being removed — the moment it happens, rather than waiting for the
     * generic ~10-15 minute staleness detection above to notice *something* stopped reporting
     * with no stated reason. Shown only while it's the freshest thing we know about this device:
     * compared against [lastSeen] rather than cleared server-side, so a tamper event is
     * automatically superseded (and stops displaying) the moment any later regular status push
     * succeeds — the same "derive honesty client-side from timestamps" approach this function
     * already uses for staleness, rather than a flag that has to be remembered to be reset.
     */
    private fun updateChildConnectionStatus(
        lastSeen: com.google.firebase.Timestamp?,
        accessibilityHealthy: Boolean,
        tamperEvent: String? = null,
        tamperEventAt: com.google.firebase.Timestamp? = null
    ) {
        val tamperIsFreshest = tamperEventAt != null &&
            (lastSeen == null || tamperEventAt.toDate().time > lastSeen.toDate().time)
        binding.tvChildStatus.text = when {
            tamperIsFreshest -> when (tamperEvent) {
                "device_admin_disabled" -> getString(R.string.child_tamper_device_admin_disabled)
                else -> getString(R.string.child_tamper_generic)
            }
            lastSeen == null -> getString(R.string.child_offline)
            System.currentTimeMillis() - lastSeen.toDate().time > STALE_THRESHOLD_MS ->
                getString(
                    R.string.child_last_seen_format,
                    java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(lastSeen.toDate())
                )
            !accessibilityHealthy -> getString(R.string.child_connected_accessibility_disabled)
            else -> getString(R.string.child_connected)
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

    /** Entry point for the parent's own "Unpair" button, and the child's "connection lost"
     *  repair screen — both perform the unpair themselves, immediately, with no approval step
     *  (the parent unpairing their own dashboard needs no one else's permission; a child whose
     *  session is already broken has no live Firestore access to file a request through). */
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

    /** Entry point for the paired child's Unpair button — routes to the old immediate behavior
     *  only for the broken-session repair screen; otherwise requires parent approval, since a
     *  live, un-gated self-unpair let a child instantly destroy the parent's own family record
     *  with no consent (see firestore.rules — a child can no longer delete families/{id} at all). */
    private fun confirmChildUnpairAction() {
        if (childSessionNeedsRepair) {
            confirmUnpair()
        } else {
            confirmRequestUnpair()
        }
    }

    private fun performUnpair() {
        val fid = currentConfig?.familyId
        showLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            if (fid != null) {
                PairingManager.unpair(fid)
            }
            clearLocalParentalState()

            withContext(Dispatchers.Main) {
                showLoading(false)
                Toast.makeText(this@ParentalControlActivity, getString(R.string.unpaired_success), Toast.LENGTH_SHORT).show()
                showRoleSelection()
            }
        }
    }

    /** Local-only teardown shared by [performUnpair] (which also deletes the remote family
     *  record itself) and the approved-unpair callback in [confirmRequestUnpair] (where the
     *  parent has already deleted the remote record via [PairingManager.unpair]). */
    private suspend fun clearLocalParentalState() {
        parentalDao.clearConfig()
        parentalDao.clearAllRestrictions()
        ParentalControlState.clear()
        SyncWorker.cancel(applicationContext)
        ParentalAuthManager.signOut()
        currentConfig = null
    }

    private fun confirmRequestUnpair() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.request_unpair))
            .setMessage(getString(R.string.request_unpair_confirm))
            .setPositiveButton(getString(R.string.request_unpair)) { _, _ ->
                sendUnpairRequest()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Files an UNPAIR request the parent must approve, instead of the child unilaterally
     * unpairing (spec: a child could previously destroy the parent's whole family record with
     * one un-gated tap — see firestore.rules for the matching families/{id} delete restriction).
     * Reuses the same requests/{id} subcollection and PENDING/APPROVED/DENIED shape the existing
     * "ask for more time" flow already uses (see BlockActivity.setupParentalTimeRequest) rather
     * than inventing new schema, distinguished by a "type" field.
     *
     * There's no separate "APPROVED" status to watch for: the parent's approval action is
     * literally calling [PairingManager.unpair], which deletes this request doc along with
     * everything else — so this doc disappearing (not a status flip) IS the approval signal.
     */
    private fun sendUnpairRequest() {
        val fid = currentConfig?.familyId ?: return
        binding.btnChildUnpair.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reqRef = firestore.collection("families").document(fid)
                    .collection("requests").document()
                reqRef.set(mapOf(
                    "type" to "UNPAIR",
                    "status" to "PENDING",
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )).await()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ParentalControlActivity, getString(R.string.unpair_request_sent), Toast.LENGTH_SHORT).show()
                }

                unpairRequestListener?.remove()
                unpairRequestListener = reqRef.addSnapshotListener { snapshot, _ ->
                    if (snapshot == null) return@addSnapshotListener
                    if (!snapshot.exists()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            clearLocalParentalState()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ParentalControlActivity, getString(R.string.unpaired_success), Toast.LENGTH_SHORT).show()
                                showRoleSelection()
                            }
                        }
                    } else if (snapshot.getString("status") == "DENIED") {
                        Toast.makeText(this@ParentalControlActivity, getString(R.string.unpair_request_denied), Toast.LENGTH_SHORT).show()
                        binding.btnChildUnpair.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ParentalControlActivity,
                        getString(R.string.error_change_not_saved, friendlyErrorMessage(e)),
                        Toast.LENGTH_LONG
                    ).show()
                    binding.btnChildUnpair.isEnabled = true
                }
            }
        }
    }

    // ── Parent account deletion (Google Play User Data policy: in-app deletion path) ──────

    /**
     * Deletes the parent's account entirely: any paired family's Firestore data (via the same
     * [PairingManager.unpair] cascade the Unpair button uses), then the Firebase Auth identity
     * itself. Reachable from both parent-facing screens (paired dashboard and the
     * signed-in-but-not-yet-paired pairing screen) since a parent can want this at either point.
     */
    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_my_account))
            .setMessage(getString(R.string.delete_account_confirm_message))
            .setPositiveButton(getString(R.string.delete_my_account)) { _, _ -> promptPasswordAndDeleteAccount() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Firebase requires a "recent login" for account deletion — a session open for a while fails
     * with FirebaseAuthRecentLoginRequiredException instead of actually deleting anything, so
     * this re-collects the password immediately before deleting rather than assuming the
     * existing session is fresh enough.
     */
    private fun promptPasswordAndDeleteAccount() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_account_reauth_title))
            .setMessage(getString(R.string.delete_account_reauth_message))
            .setView(container)
            .setPositiveButton(getString(R.string.delete_my_account)) { _, _ ->
                val password = input.text.toString()
                if (password.isEmpty()) {
                    Toast.makeText(this, getString(R.string.error_fill_fields), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performDeleteAccount(password)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performDeleteAccount(password: String) {
        showLoading(true)
        val fid = currentConfig?.familyId
        lifecycleScope.launch(Dispatchers.IO) {
            val reauth = ParentalAuthManager.reauthenticateWithPassword(password)
            if (reauth.isFailure) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@ParentalControlActivity,
                        getString(R.string.error_change_not_saved, friendlyErrorMessage(reauth.exceptionOrNull())),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            // Delete Firestore data BEFORE the Auth identity — Firestore rules key everything off
            // request.auth.uid, which stops existing the instant deleteCurrentUser() succeeds, so
            // doing it in the other order would leave this family's data orphaned and undeletable.
            if (fid != null) {
                PairingManager.unpair(fid)
            }

            val deleted = ParentalAuthManager.deleteCurrentUser()
            if (deleted.isFailure) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@ParentalControlActivity,
                        getString(R.string.error_change_not_saved, friendlyErrorMessage(deleted.exceptionOrNull())),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            clearLocalParentalState()

            withContext(Dispatchers.Main) {
                showLoading(false)
                Toast.makeText(this@ParentalControlActivity, getString(R.string.delete_account_success), Toast.LENGTH_LONG).show()
                showRoleSelection()
            }
        }
    }
}
