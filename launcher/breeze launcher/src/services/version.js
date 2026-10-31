/**
 * Launcher versions, ordered as semver orders them, the way the API's
 * /versions/check does (server.js cmpVersions): 1.0.38 < 1.1.0-test.1 <
 * 1.1.0-test.2 < 1.1.0. A test build is never told a stable build of the
 * same number is older, and is offered it when it comes out.
 */
function split(value) {
  const s = String(value || "0").trim().replace(/^v/i, "").split("+")[0];
  const dash = s.indexOf("-");
  const core = (dash < 0 ? s : s.slice(0, dash)).split(".").map((part) => parseInt(part, 10) || 0);
  return { core, pre: dash < 0 ? "" : s.slice(dash + 1) };
}

/** Negative, zero or positive as a is older than, the same as, or newer than b. */
export function compareVersions(a, b) {
  const A = split(a);
  const B = split(b);
  for (let i = 0; i < Math.max(A.core.length, B.core.length, 3); i += 1) {
    const d = (A.core[i] || 0) - (B.core[i] || 0);
    if (d !== 0) return d;
  }
  if (!A.pre || !B.pre) return (A.pre ? -1 : 0) + (B.pre ? 1 : 0);
  const pa = A.pre.split(".");
  const pb = B.pre.split(".");
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const x = pa[i];
    const y = pb[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const nx = /^\d+$/.test(x);
    const ny = /^\d+$/.test(y);
    if (nx && ny) {
      const d = parseInt(x, 10) - parseInt(y, 10);
      if (d !== 0) return d;
    } else if (nx !== ny) {
      return nx ? -1 : 1;
    } else if (x !== y) {
      return x > y ? 1 : -1;
    }
  }
  return 0;
}

export function isNewerVersion(remote, local) {
  return compareVersions(remote, local) > 0;
}

/** "1.1.0-test.3" is test build 3 of 1.1.0, labelled 1.1.0_test_3; null for anything else. */
export function testBuildLabel(version) {
  const m = /^(\d+\.\d+\.\d+)-test\.(\d+)$/.exec(String(version || ""));
  return m ? `${m[1]}_test_${m[2]}` : null;
}
