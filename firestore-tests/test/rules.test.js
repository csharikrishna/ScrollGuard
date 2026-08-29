/**
 * Firestore Security Rules emulator test suite for ScrollGuard's parental-control feature.
 *
 * Covers the allow/deny matrix required by ScrollGuard_Parental_Control_MVP.md Part E.3 / H:
 * valid parent, unrelated parent, the child, unauthenticated user, malicious child attempting
 * parent-field writes, and expired/reused pairing codes — plus the specific hijack/enumeration/
 * bootstrap holes found and fixed during the production-readiness audit (see AUDIT_PROGRESS.md
 * seed leads 1 and 2, and the "config/current bootstrap" finding).
 *
 * Run via `npm test` from this directory, against a running Firestore emulator
 * (`firebase emulators:exec "npm --prefix firestore-tests test"` from the repo root, or start
 * the emulator separately and just `npm test` here while it's up).
 */
const fs = require("fs");
const path = require("path");
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  serverTimestamp,
  Timestamp,
} = require("firebase/firestore");

const PROJECT_ID = "scrollguard-rules-test";
const PARENT_UID = "parent-uid-1";
const CHILD_UID = "child-uid-1";
const OTHER_UID = "unrelated-uid-1";
const FAMILY_ID = "family-1";
const CODE = "ABC234";

let testEnv;

function future(ms) {
  return Timestamp.fromMillis(Date.now() + ms);
}

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: fs.readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8"),
      host: "127.0.0.1",
      port: 8080,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

/** Seeds a family doc bypassing rules, as tests that assume pre-existing state need. */
async function seedFamily({ parentUid = null, childUid = CHILD_UID, familyId = FAMILY_ID } = {}) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `families/${familyId}`), {
      parentUid,
      childUid,
      childDeviceName: "Test Phone",
      createdAt: serverTimestamp(),
    });
  });
}

async function seedPairingCode({
  code = CODE,
  familyId = FAMILY_ID,
  consumed = false,
  expiresInMs = 5 * 60 * 1000,
  parentUid = null,
} = {}) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `pairing/${code}`), {
      familyId,
      parentUid,
      createdAt: serverTimestamp(),
      expiresAt: future(expiresInMs),
      consumed,
    });
  });
}

async function seedConfig({ familyId = FAMILY_ID, enabled = false } = {}) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `families/${familyId}/config/current`), {
      enabled,
      configVersion: 0,
      updatedAt: serverTimestamp(),
    });
  });
}

describe("pairing/{code}", () => {
  it("lets a child create a pairing code only for a family it owns as childUid", async () => {
    await seedFamily({ parentUid: null, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertSucceeds(
      setDoc(doc(childDb, `pairing/${CODE}`), {
        familyId: FAMILY_ID,
        parentUid: null,
        createdAt: serverTimestamp(),
        expiresAt: future(300000),
        consumed: false,
      })
    );
  });

  it("[fixed hole] denies creating a pairing code for a family the caller does not own", async () => {
    await seedFamily({ parentUid: null, childUid: CHILD_UID });
    const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertFails(
      setDoc(doc(otherDb, `pairing/${CODE}`), {
        familyId: FAMILY_ID,
        parentUid: null,
        createdAt: serverTimestamp(),
        expiresAt: future(300000),
        consumed: false,
      })
    );
  });

  it("denies an unauthenticated client from creating a pairing code", async () => {
    await seedFamily({ parentUid: null, childUid: CHILD_UID });
    const anonDb = testEnv.unauthenticatedContext().firestore();
    await assertFails(
      setDoc(doc(anonDb, `pairing/${CODE}`), {
        familyId: FAMILY_ID,
        parentUid: null,
        createdAt: serverTimestamp(),
        expiresAt: future(300000),
        consumed: false,
      })
    );
  });

  it("lets an authenticated client GET a pairing code it already knows", async () => {
    await seedFamily();
    await seedPairingCode();
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertSucceeds(getDoc(doc(parentDb, `pairing/${CODE}`)));
  });

  it("[fixed hole] denies LISTING the pairing collection — codes cannot be enumerated", async () => {
    await seedFamily();
    await seedPairingCode();
    const someoneDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertFails(getDocs(collection(someoneDb, "pairing")));
  });

  it("lets a parent claim an unconsumed, unexpired code (consumed+parentUid only)", async () => {
    await seedFamily({ parentUid: null });
    await seedPairingCode();
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertSucceeds(
      updateDoc(doc(parentDb, `pairing/${CODE}`), { consumed: true, parentUid: PARENT_UID })
    );
  });

  it("[fixed hole] denies a claim update that smuggles in other field changes", async () => {
    await seedFamily({ parentUid: null });
    await seedPairingCode();
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertFails(
      updateDoc(doc(parentDb, `pairing/${CODE}`), {
        consumed: true,
        parentUid: PARENT_UID,
        familyId: "some-other-family",
      })
    );
  });

  it("denies re-claiming an already-consumed code", async () => {
    await seedFamily({ parentUid: PARENT_UID });
    await seedPairingCode({ consumed: true, parentUid: PARENT_UID });
    const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertFails(
      updateDoc(doc(otherDb, `pairing/${CODE}`), { consumed: true, parentUid: OTHER_UID })
    );
  });

  it("denies claiming an expired code", async () => {
    await seedFamily({ parentUid: null });
    await seedPairingCode({ expiresInMs: -1000 });
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertFails(
      updateDoc(doc(parentDb, `pairing/${CODE}`), { consumed: true, parentUid: PARENT_UID })
    );
  });

  it("lets anyone delete an expired code (cleanup)", async () => {
    await seedFamily();
    await seedPairingCode({ expiresInMs: -1000 });
    const someoneDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertSucceeds(deleteDoc(doc(someoneDb, `pairing/${CODE}`)));
  });

  it("denies an unrelated user deleting a still-valid, unconsumed code", async () => {
    await seedFamily();
    await seedPairingCode();
    const someoneDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertFails(deleteDoc(doc(someoneDb, `pairing/${CODE}`)));
  });
});

describe("families/{familyId}", () => {
  it("lets a child create its own family doc with parentUid null", async () => {
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertSucceeds(
      setDoc(doc(childDb, `families/${FAMILY_ID}`), {
        childUid: CHILD_UID,
        parentUid: null,
        childDeviceName: "Phone",
        createdAt: serverTimestamp(),
      })
    );
  });

  it("[fixed hole] denies a child self-assigning a parentUid at creation", async () => {
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertFails(
      setDoc(doc(childDb, `families/${FAMILY_ID}`), {
        childUid: CHILD_UID,
        parentUid: OTHER_UID,
        childDeviceName: "Phone",
        createdAt: serverTimestamp(),
      })
    );
  });

  it("denies creating a family doc claiming someone else's childUid", async () => {
    const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertFails(
      setDoc(doc(otherDb, `families/${FAMILY_ID}`), {
        childUid: CHILD_UID,
        parentUid: null,
        childDeviceName: "Phone",
        createdAt: serverTimestamp(),
      })
    );
  });

  it("lets the parent and child read the family doc; denies an unrelated user", async () => {
    await seedFamily({ parentUid: PARENT_UID });
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertSucceeds(getDoc(doc(parentDb, `families/${FAMILY_ID}`)));
    await assertSucceeds(getDoc(doc(childDb, `families/${FAMILY_ID}`)));
    await assertFails(getDoc(doc(otherDb, `families/${FAMILY_ID}`)));
  });

  it("denies an unauthenticated user reading a family doc", async () => {
    await seedFamily({ parentUid: PARENT_UID });
    const anonDb = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(anonDb, `families/${FAMILY_ID}`)));
  });

  it("lets the claiming parent perform the one-time initial bind (Case 1)", async () => {
    await seedFamily({ parentUid: null });
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertSucceeds(updateDoc(doc(parentDb, `families/${FAMILY_ID}`), { parentUid: PARENT_UID }));
  });

  it("denies the initial-claim update if it also changes childUid", async () => {
    await seedFamily({ parentUid: null });
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertFails(
      updateDoc(doc(parentDb, `families/${FAMILY_ID}`), { parentUid: PARENT_UID, childUid: PARENT_UID })
    );
  });

  it("[CRITICAL fixed hole] denies an already-paired CHILD rewriting parentUid (family hijack)", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertFails(updateDoc(doc(childDb, `families/${FAMILY_ID}`), { parentUid: OTHER_UID }));
  });

  it("[CRITICAL fixed hole] denies an already-paired PARENT rewriting childUid (family hijack)", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertFails(updateDoc(doc(parentDb, `families/${FAMILY_ID}`), { childUid: OTHER_UID }));
  });

  it("lets the child update only its own device name after pairing", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertSucceeds(
      updateDoc(doc(childDb, `families/${FAMILY_ID}`), { childDeviceName: "New Phone Name" })
    );
  });

  it("denies the parent updating the child's device name (not parent-owned)", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertFails(
      updateDoc(doc(parentDb, `families/${FAMILY_ID}`), { childDeviceName: "Renamed By Parent" })
    );
  });

  it("lets either the parent or the child delete (unpair) the family doc", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertSucceeds(deleteDoc(doc(childDb, `families/${FAMILY_ID}`)));
  });

  it("denies an unrelated user deleting a family doc", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertFails(deleteDoc(doc(otherDb, `families/${FAMILY_ID}`)));
  });
});

describe("families/{familyId}/config/current (parent-owned, child bootstraps once)", () => {
  it("[fixed bug] lets the CHILD bootstrap the initial disabled config stub pre-pairing", async () => {
    await seedFamily({ parentUid: null, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertSucceeds(
      setDoc(doc(childDb, `families/${FAMILY_ID}/config/current`), {
        enabled: false,
        configVersion: 0,
        updatedAt: serverTimestamp(),
      })
    );
  });

  it("denies the child bootstrapping the config stub already enabled", async () => {
    await seedFamily({ parentUid: null, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertFails(
      setDoc(doc(childDb, `families/${FAMILY_ID}/config/current`), {
        enabled: true,
        configVersion: 0,
        updatedAt: serverTimestamp(),
      })
    );
  });

  it("[malicious child attempting a parent-field write] denies the child updating config/current once it exists", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    await seedConfig();
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertFails(updateDoc(doc(childDb, `families/${FAMILY_ID}/config/current`), { enabled: true }));
  });

  it("lets the parent write config/current", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    await seedConfig();
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertSucceeds(updateDoc(doc(parentDb, `families/${FAMILY_ID}/config/current`), { enabled: true }));
  });

  it("denies an unrelated user writing config/current", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    await seedConfig();
    const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertFails(updateDoc(doc(otherDb, `families/${FAMILY_ID}/config/current`), { enabled: true }));
  });

  it("lets the parent write a per-app restriction; denies the child", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    await seedConfig();
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    await assertSucceeds(
      setDoc(doc(parentDb, `families/${FAMILY_ID}/config/current/apps/com.instagram.android`), {
        enabled: true,
        label: "Instagram",
        allowanceSeconds: 3600,
      })
    );
    await assertFails(
      setDoc(doc(childDb, `families/${FAMILY_ID}/config/current/apps/com.tiktok.android`), {
        enabled: true,
        label: "TikTok",
        allowanceSeconds: 3600,
      })
    );
  });
});

describe("families/{familyId}/status/current and catalog/current (child-owned)", () => {
  it("lets the child write status; [malicious parent attempting a child-field write] denies the parent", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertSucceeds(
      setDoc(doc(childDb, `families/${FAMILY_ID}/status/current`), {
        consumedEpochDay: 0,
        lastSeen: serverTimestamp(),
        syncState: "SYNCED",
        accessibilityHealthy: true,
      })
    );
    await assertFails(
      setDoc(doc(parentDb, `families/${FAMILY_ID}/status/current`), {
        consumedEpochDay: 0,
        lastSeen: serverTimestamp(),
        syncState: "SYNCED",
        accessibilityHealthy: true,
      })
    );
  });

  it("lets the parent read status; denies an unrelated user", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `families/${FAMILY_ID}/status/current`), { consumedEpochDay: 0 });
    });
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
    await assertSucceeds(getDoc(doc(parentDb, `families/${FAMILY_ID}/status/current`)));
    await assertFails(getDoc(doc(otherDb, `families/${FAMILY_ID}/status/current`)));
  });

  it("lets the child write the app catalog; denies the parent", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
    const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
    await assertSucceeds(
      setDoc(doc(childDb, `families/${FAMILY_ID}/catalog/current`), {
        apps: [{ packageName: "com.instagram.android", label: "Instagram" }],
        updatedAt: serverTimestamp(),
      })
    );
    await assertFails(
      setDoc(doc(parentDb, `families/${FAMILY_ID}/catalog/current`), {
        apps: [],
        updatedAt: serverTimestamp(),
      })
    );
  });
});

describe("unauthenticated access is denied everywhere", () => {
  it("denies unauthenticated reads/writes across every subtree", async () => {
    await seedFamily({ parentUid: PARENT_UID, childUid: CHILD_UID });
    await seedConfig();
    const anonDb = testEnv.unauthenticatedContext().firestore();
    await assertFails(updateDoc(doc(anonDb, `families/${FAMILY_ID}/config/current`), { enabled: true }));
    await assertFails(setDoc(doc(anonDb, `families/${FAMILY_ID}/status/current`), { consumedEpochDay: 0 }));
    await assertFails(setDoc(doc(anonDb, `families/${FAMILY_ID}/catalog/current`), { apps: [] }));
    await assertFails(getDoc(doc(anonDb, `families/${FAMILY_ID}`)));
  });
});
