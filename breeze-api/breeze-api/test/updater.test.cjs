'use strict';
/**
 * Updating from one launcher release to the next (1.0.23).
 *
 * The owner's installed 1.0.22 build could not update: the update check said
 * there was nothing, and following the manifest's download link gave a 404.
 * Both were true statements about a server with no artifact on it, dressed up
 * badly:
 *
 *   - the flat manifest downloadUrl fell back to breezeclient.net/downloads,
 *     a web page, sitting next to a fileName and sha256 describing an .exe;
 *   - a build named in the manifest was advertised whether or not the file had
 *     been uploaded, so the URL 404'd.
 *
 * These tests run the real routes with a real file on disk, and again without
 * one. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { startApi } = require('./support/harness.cjs');

// A file that begins like a Windows installer, so it is a plausible artifact.
const INSTALLER = Buffer.concat([Buffer.from('MZ'), crypto.randomBytes(2048)]);
const SHA256 = crypto.createHash('sha256').update(INSTALLER).digest('hex');
const OWNER = { uuid: 'f1111111111111111111111111111111', username: 'Owner', role: 'owner' };

test('a published release is offered to an older build, with a URL that serves the file', async () => {
  const api = await startApi({
    name: 'updater-published',
    seed: { users: [OWNER] },
    env: { LATEST_LAUNCHER_VERSION: '1.0.23', LATEST_LAUNCHER_DATE: '2026-09-25' },
  });
  try {
    const target = path.join(api.dataDir, 'versions', 'launcher', 'windows', 'Breeze-Client-1.0.23.exe');
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, INSTALLER);

    const check = await api.get('/versions/check?current=1.0.22&platform=windows');
    assert.equal(check.status, 200, check.text.slice(0, 200));
    assert.equal(check.body.upToDate, false, '1.0.22 is not the latest');
    const update = check.body.update;
    assert.ok(update, 'an update is offered');
    assert.equal(update.version, '1.0.23');
    assert.equal(update.available, true);
    assert.equal(update.sha256, SHA256, 'the checksum describes the bytes on disk');
    assert.match(update.url, /^https:\/\//, 'the launcher refuses anything that is not https');
    assert.match(update.fileName, /Breeze-Client-1\.0\.23\.exe$/);

    // Following the advertised URL must produce the installer itself.
    const url = new URL(update.url);
    const file = await api.get(url.pathname);
    assert.equal(file.status, 200, file.text.slice(0, 120));
    assert.equal(
      crypto.createHash('sha256').update(Buffer.from(file.text, 'binary')).digest('hex').length,
      64,
      'a body came back',
    );
    assert.ok(file.text.startsWith('MZ'), 'an installer, not an API response or a web page');
  } finally {
    await api.stop();
  }
});

test('a release named in the manifest but never uploaded is not advertised', async () => {
  const api = await startApi({
    name: 'updater-missing',
    seed: { users: [OWNER] },
    env: { LATEST_LAUNCHER_VERSION: '1.0.23' },
  });
  try {
    const versions = await api.get('/versions');
    assert.equal(versions.status, 200);
    const launcher = versions.body.launcher;

    // The flat fields are what older clients read.
    assert.equal(launcher.downloadUrl, null, 'nothing to download means null, not a web page');
    assert.doesNotMatch(
      JSON.stringify(launcher.downloadUrl),
      /downloads\/latest/,
      'the human download page is never offered as the installer',
    );
    assert.match(launcher.websiteUrl, /downloads\/latest/, 'the page is still there for a person');
    assert.equal(launcher.platforms.windows.available, false);
    assert.equal(launcher.platforms.windows.downloadUrl, null);

    const check = await api.get('/versions/check?current=1.0.22&platform=windows');
    assert.equal(check.body.stable.available, false);
    assert.equal(check.body.stable.url, null, 'no URL for a build nobody can download');
    assert.equal(check.body.update, null, 'and no update is offered');
  } finally {
    await api.stop();
  }
});

test('every published format carries its own checksum, and Linux carries all of them', async () => {
  const api = await startApi({
    name: 'updater-formats',
    seed: { users: [OWNER] },
    env: { LATEST_LAUNCHER_VERSION: '1.0.23' },
  });
  try {
    const root = path.join(api.dataDir, 'versions', 'launcher');
    const written = {
      'linux/appimage/Breeze-Client-1.0.23.AppImage': Buffer.concat([Buffer.from([0x7f, 0x45, 0x4c, 0x46]), crypto.randomBytes(512)]),
      'linux/deb/Breeze-Client-1.0.23.deb': crypto.randomBytes(512),
      'linux/rpm/Breeze-Client-1.0.23.rpm': crypto.randomBytes(512),
      'linux/flatpak/Breeze-Client-1.0.23.flatpak': crypto.randomBytes(512),
      'macos/Breeze-Client-1.0.23.dmg': crypto.randomBytes(512),
    };
    for (const [rel, bytes] of Object.entries(written)) {
      const full = path.join(root, rel);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, bytes);
    }

    const versions = await api.get('/versions');
    const linux = versions.body.launcher.platforms.linux;
    for (const format of ['appimage', 'deb', 'rpm', 'flatpak']) {
      const entry = linux.formats[format];
      assert.ok(entry, `${format} is described`);
      assert.equal(entry.available, true, `${format} is published`);
      const expected = crypto.createHash('sha256')
        .update(written[`linux/${format}/Breeze-Client-1.0.23.${entry.ext}`])
        .digest('hex');
      assert.equal(entry.sha256, expected, `${format} checksum matches its own bytes`);
      assert.match(entry.downloadUrl, new RegExp(`${format}/`), `${format} has its own URL`);
    }
    assert.equal(versions.body.launcher.platforms.macos.available, true);
    assert.equal(versions.body.launcher.platforms.windows.available, false, 'windows was not uploaded here');

    // A Linux client that was installed from a .deb asks for a .deb back.
    const deb = await api.get('/versions/check?current=1.0.22&platform=linux&format=deb');
    assert.equal(deb.body.format, 'deb', 'the check answers in the format that was asked for');
    assert.match(deb.body.update.fileName, /\.deb$/, 'and offers the .deb, not the AppImage');

    // Asking for nothing in particular gets the format the platform leads with.
    const any = await api.get('/versions/check?current=1.0.22&platform=linux');
    assert.equal(any.body.format, 'appimage');
    assert.match(any.body.update.fileName, /\.AppImage$/);
  } finally {
    await api.stop();
  }
});

test('a checksum follows the bytes, not the manifest', async () => {
  const api = await startApi({
    name: 'updater-rebuild',
    seed: { users: [OWNER] },
    env: {
      LATEST_LAUNCHER_VERSION: '1.0.23',
      // A stale hash left in the environment must not win over the real file.
      LATEST_LAUNCHER_WINDOWS_SHA256: '0'.repeat(64),
    },
  });
  try {
    const target = path.join(api.dataDir, 'versions', 'launcher', 'windows', 'Breeze-Client-1.0.23.exe');
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, INSTALLER);
    const first = await api.get('/versions');
    assert.equal(first.body.launcher.platforms.windows.sha256, SHA256, 'hashed from disk');

    // Rebuild the artifact: the manifest must describe the new bytes.
    const rebuilt = Buffer.concat([Buffer.from('MZ'), crypto.randomBytes(4096)]);
    fs.writeFileSync(target, rebuilt);
    const second = await api.get('/versions');
    assert.equal(
      second.body.launcher.platforms.windows.sha256,
      crypto.createHash('sha256').update(rebuilt).digest('hex'),
      'a rebuilt artifact gets a fresh checksum',
    );
  } finally {
    await api.stop();
  }
});

test('one Linux folder, artifacts named the way the build system names them', async () => {
  // The owner's request: drop the Linux builds in the Linux folder and have the
  // admin panel show each format on its own, instead of calling everything an
  // AppImage. These are the names Tauri and cargo actually produce.
  const api = await startApi({
    name: 'linux-folder',
    seed: { users: [OWNER] },
    env: { LATEST_LAUNCHER_VERSION: '1.0.23' },
  });
  try {
    const linux = path.join(api.dataDir, 'versions', 'launcher', 'linux');
    fs.mkdirSync(linux, { recursive: true });
    const drops = {
      'Breeze Client_1.0.23_amd64.AppImage': Buffer.concat([Buffer.from([0x7f, 0x45, 0x4c, 0x46]), crypto.randomBytes(256)]),
      'breeze-client_1.0.23_amd64.deb': crypto.randomBytes(256),
      'breeze-client-1.0.23-1.x86_64.rpm': crypto.randomBytes(256),
      'dev.breeze.Client_1.0.23_x86_64.flatpak': crypto.randomBytes(256),
    };
    for (const [name, bytes] of Object.entries(drops)) fs.writeFileSync(path.join(linux, name), bytes);

    const res = await api.get('/versions');
    const formats = res.body.launcher.platforms.linux.formats;
    for (const [format, name] of Object.entries({
      appimage: 'Breeze Client_1.0.23_amd64.AppImage',
      deb: 'breeze-client_1.0.23_amd64.deb',
      rpm: 'breeze-client-1.0.23-1.x86_64.rpm',
      flatpak: 'dev.breeze.Client_1.0.23_x86_64.flatpak',
    })) {
      const entry = formats[format];
      assert.equal(entry.available, true, `${format} was found in the folder`);
      assert.equal(entry.fileName, `linux/${name}`, `${format} points at the real file`);
      assert.equal(entry.version, '1.0.23', `${format} version read from the filename`);
      assert.equal(entry.arch, 'x86_64', `${format} architecture read from the filename`);
      assert.equal(
        entry.sha256,
        crypto.createHash('sha256').update(drops[name]).digest('hex'),
        `${format} checksum is of that file`,
      );
      // And the URL really serves those bytes.
      const file = await api.get(new URL(entry.downloadUrl).pathname);
      assert.equal(file.status, 200, `${format} downloads`);
    }
    assert.equal(res.body.launcher.platforms.linux.anyFormatAvailable, true);
  } finally {
    await api.stop();
  }
});

test('an older build in the folder never outranks the release being published', async () => {
  const api = await startApi({
    name: 'linux-older',
    seed: { users: [OWNER] },
    env: { LATEST_LAUNCHER_VERSION: '1.0.23' },
  });
  try {
    const linux = path.join(api.dataDir, 'versions', 'launcher', 'linux');
    fs.mkdirSync(linux, { recursive: true });
    fs.writeFileSync(path.join(linux, 'breeze-client_1.0.21_amd64.deb'), crypto.randomBytes(128));
    fs.writeFileSync(path.join(linux, 'breeze-client_1.0.23_amd64.deb'), crypto.randomBytes(128));
    const res = await api.get('/versions');
    assert.equal(res.body.launcher.platforms.linux.formats.deb.version, '1.0.23');
    assert.match(res.body.launcher.platforms.linux.formats.deb.fileName, /1\.0\.23/);
  } finally {
    await api.stop();
  }
});

test('a creator offered a test build can actually download it', async () => {
  // The updater sends no Authorization header, so a test build offered by the
  // check used to answer 401 when the launcher fetched it. An installed build
  // cannot be changed after the fact, so the URL has to carry its own proof.
  const CREATOR = { uuid: 'f2222222222222222222222222222222', username: 'Maker', role: 'creator' };
  const api = await startApi({ name: 'updater-testing', seed: { users: [CREATOR] } });
  try {
    const build = path.join(api.dataDir, 'versions', 'launcher', 'testing', 'beta', 'windows', 'Breeze-Client-1.0.24.exe');
    fs.mkdirSync(path.dirname(build), { recursive: true });
    fs.writeFileSync(build, INSTALLER);

    const check = await api.get('/versions/check?current=1.0.22&platform=windows', { token: api.token(CREATOR) });
    assert.equal(check.status, 200, check.text.slice(0, 200));
    const update = check.body.update;
    assert.ok(update, 'a creator is offered the beta build');
    assert.equal(update.channel, 'beta');
    assert.equal(update.version, '1.0.24');
    assert.match(update.url, /[?&]t=/, 'the URL carries its own proof');
    assert.equal(update.sha256, SHA256);

    const url = new URL(update.url);
    const file = await api.get(url.pathname + url.search);
    assert.equal(file.status, 200, 'the updater can fetch it with no header');
    assert.ok(file.text.startsWith('MZ'), 'and gets the installer');

    // A player is never offered it, and cannot fetch it.
    const PLAYER = { uuid: 'f3333333333333333333333333333333', username: 'Player', role: 'user' };
    const theirs = await api.get('/versions/check?current=1.0.22&platform=windows', { token: api.token(PLAYER) });
    assert.equal(theirs.body.update, null, 'test channels are not offered to players');
    const refused = await api.get(url.pathname);
    assert.equal(refused.status, 401, 'and the file still needs proof');
  } finally {
    await api.stop();
  }
});

test('every role above user is offered test builds, a user only published releases', async () => {
  // The owner's rule: creators, developers, admins and owners get the testing
  // channels, normal users only what is in the release folder. With nothing
  // in the release folder a user is told there is no update.
  const PEOPLE = [
    { uuid: 'f4444444444444444444444444444441', username: 'Maker', role: 'creator' },
    { uuid: 'f4444444444444444444444444444442', username: 'Dev', role: 'developer' },
    { uuid: 'f4444444444444444444444444444443', username: 'Admin', role: 'admin' },
    { uuid: 'f4444444444444444444444444444444', username: 'Boss', role: 'owner' },
  ];
  const USER = { uuid: 'f4444444444444444444444444444445', username: 'Player', role: 'user' };
  const api = await startApi({ name: 'updater-roles', seed: { users: [...PEOPLE, USER] } });
  try {
    const build = path.join(api.dataDir, 'versions', 'launcher', 'testing', 'pre-beta', 'windows', 'Breeze-Client-1.0.26.exe');
    fs.mkdirSync(path.dirname(build), { recursive: true });
    fs.writeFileSync(build, INSTALLER);

    for (const person of PEOPLE) {
      const check = await api.get('/versions/check?current=1.0.25&platform=windows', { token: api.token(person) });
      assert.equal(check.status, 200, check.text.slice(0, 200));
      const update = check.body.update;
      assert.ok(update, `${person.role} is offered the pre-beta build`);
      assert.equal(update.channel, 'pre-beta');
      assert.equal(update.version, '1.0.26');
      assert.equal(update.sha256, SHA256, 'with the checksum the updater requires');
      const url = new URL(update.url);
      assert.equal(url.pathname, '/versions/launcher/testing/pre-beta/windows/Breeze-Client-1.0.26.exe');
      const file = await api.get(url.pathname + url.search);
      assert.equal(file.status, 200, `${person.role} can fetch it with no header`);
      assert.ok(file.text.startsWith('MZ'));
    }

    const theirs = await api.get('/versions/check?current=1.0.25&platform=windows', { token: api.token(USER) });
    assert.equal(theirs.status, 200);
    assert.equal(theirs.body.role, 'user');
    assert.equal(theirs.body.update, null, 'a user is not offered the test build');
    assert.equal(theirs.body.upToDate, true, 'and is told there is no update while the release folder is empty');

    // Even holding a link made for someone else, a user cannot use it.
    const owner = await api.get('/versions/check?current=1.0.25&platform=windows', { token: api.token(PEOPLE[3]) });
    const ownersLink = new URL(owner.body.update.url);
    const demoted = { ...USER };
    const asUser = await api.get(`${ownersLink.pathname}`, { token: api.token(demoted) });
    assert.equal(asUser.status, 403, 'a user with their own token is refused the test build');
  } finally {
    await api.stop();
  }
});

test('a test build is offered when the release it shares a number with was never uploaded', async () => {
  // The way a release is prepared: LATEST_LAUNCHER_VERSION is raised to the
  // new number and the installer goes into a testing channel first, with
  // nothing in the release folder yet. The check started from that declared
  // release, which has no file, and only took a test build that was strictly
  // newer, so testers were offered nothing (and an older launcher showed an
  // update whose download led nowhere).
  const OWNER2 = { uuid: 'f5555555555555555555555555555551', username: 'Boss', role: 'owner' };
  const USER = { uuid: 'f5555555555555555555555555555552', username: 'Player', role: 'user' };
  const api = await startApi({
    name: 'updater-declared',
    seed: { users: [OWNER2, USER] },
    env: { LATEST_LAUNCHER_VERSION: '1.0.26' },
  });
  try {
    const build = path.join(api.dataDir, 'versions', 'launcher', 'testing', 'pre-release', 'windows', 'Breeze-Client-1.0.26.exe');
    fs.mkdirSync(path.dirname(build), { recursive: true });
    fs.writeFileSync(build, INSTALLER);

    const check = await api.get('/versions/check?current=1.0.25&platform=windows', { token: api.token(OWNER2) });
    assert.equal(check.status, 200, check.text.slice(0, 200));
    assert.equal(check.body.stable.version, '1.0.26');
    assert.equal(check.body.stable.available, false, 'nothing in the release folder');
    const update = check.body.update;
    assert.ok(update, 'the owner is offered the pre-release build');
    assert.equal(update.channel, 'pre-release');
    assert.equal(update.version, '1.0.26');
    assert.equal(update.sha256, SHA256);
    const url = new URL(update.url);
    const file = await api.get(url.pathname + url.search);
    assert.equal(file.status, 200);

    const theirs = await api.get('/versions/check?current=1.0.25&platform=windows', { token: api.token(USER) });
    assert.equal(theirs.body.update, null, 'a user still gets nothing until the release is uploaded');
  } finally {
    await api.stop();
  }
});

test('the names the release workflow actually produces are all recognised', async () => {
  // The other Linux test uses the names Tauri writes before CI renames them.
  // These are the names that come out of the Release workflow and land on the
  // API host, taken from the v1.0.24 draft release. The point of artifact
  // discovery is that these need no renaming by hand, so the exact strings are
  // worth pinning: a change to the workflow's collect step that broke them
  // would otherwise only show up as Linux quietly having no builds.
  const api = await startApi({
    name: 'release-names',
    seed: { users: [OWNER] },
    env: { LATEST_LAUNCHER_VERSION: '1.0.24' },
  });
  try {
    const drop = (os, name, bytes) => {
      const dir = path.join(api.dataDir, 'versions', 'launcher', os);
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, name), bytes);
    };
    drop('windows', 'Breeze-Client-1.0.24-x86_64.exe', Buffer.concat([Buffer.from('MZ'), crypto.randomBytes(256)]));
    drop('macos', 'Breeze-Client-1.0.24-universal.dmg', crypto.randomBytes(256));
    drop('linux', 'Breeze-Client-1.0.24-x86_64.AppImage', Buffer.concat([Buffer.from([0x7f, 0x45, 0x4c, 0x46]), crypto.randomBytes(256)]));
    drop('linux', 'Breeze-Client-1.0.24-x86_64.deb', crypto.randomBytes(256));
    drop('linux', 'Breeze-Client-1.0.24-x86_64.rpm', crypto.randomBytes(256));

    const { platforms } = (await api.get('/versions')).body.launcher;

    for (const os of ['windows', 'macos', 'linux']) {
      assert.equal(platforms[os].available, true, `${os} was found`);
      assert.equal(platforms[os].version, '1.0.24', `${os} version read from the filename`);
    }
    // macOS ships one universal binary rather than a per-architecture build.
    assert.equal(platforms.macos.arch, 'universal');
    assert.equal(platforms.windows.arch, 'x86_64');

    for (const format of ['appimage', 'deb', 'rpm']) {
      const entry = platforms.linux.formats[format];
      assert.equal(entry.available, true, `${format} was found in the one Linux folder`);
      assert.equal(entry.arch, 'x86_64', `${format} architecture read from the filename`);
      assert.equal(entry.version, '1.0.24', `${format} version read from the filename`);
    }

    // And an installed 1.0.23 is offered the new one, per platform.
    for (const os of ['windows', 'macos', 'linux']) {
      const check = await api.get(`/versions/check?current=1.0.23&platform=${os}`);
      assert.equal(check.body.update.available, true, `${os} is offered 1.0.24`);
      assert.equal(check.body.update.version, '1.0.24');
    }
  } finally {
    await api.stop();
  }
});
