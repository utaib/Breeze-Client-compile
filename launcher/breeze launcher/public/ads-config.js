/* ============================================================
   Breeze AdSense configuration — THE ONLY FILE YOU EDIT
   ------------------------------------------------------------
   Paste your Google AdSense IDs here once. Every ad surface in
   the launcher (ads.html … ads6.html) loads this file, so you
   never edit the individual ad pages. See docs: ADSENSE.md
   ============================================================ */
window.BREEZE_ADS = {
  // Your AdSense Publisher ID. Looks like "ca-pub-XXXXXXXXXXXXXXXX".
  // Find it in AdSense → Account → Settings → "Publisher ID".
  client: "ca-pub-3363746666566736",

  // One Ad-unit Slot ID per placement. Each is the 10-digit number
  // from AdSense → Ads → By ad unit → (your unit) → "data-ad-slot".
  // Index maps to the file: slots[0] = ads.html, slots[1] = ads1.html, …
  // Each must be exactly 10 digits with no spaces. The website uses a
  // different set so per-placement reporting stays readable; the last two are
  // reused from the website only because there are not enough units yet.
  slots: [
    "3678085191", // ads.html
    "7253413836", // ads1.html
    "8255624455", // ads2.html
    "4627250496", // ads3.html
    "4464553253", // ads4.html
    "5011493953", // ads5.html  reused from the website support page
    "2545112719", // ads6.html  reused from the website changelog page
  ],

  // Leave false until your AdSense account AND site are approved. While
  // false, ad frames show a neutral "Ad space" placeholder and never call
  // Google, so development and unapproved builds stay clean.
  enabled: true,
};

/* ---- Loader (do not edit below) --------------------------------------- */
(function () {
  var cfg = window.BREEZE_ADS || {};
  var idx = window.BREEZE_AD_SLOT_INDEX || 0;
  var slot = (cfg.slots || [])[idx];
  var ins = document.querySelector("ins.adsbygoogle");
  var wrap = document.querySelector(".ad-wrap");
  var placeholder = function () {
    if (ins) ins.style.display = "none";
    if (wrap) wrap.innerHTML = '<span class="ad-fallback">Ad space</span>';
  };

  // The slot is validated as strictly as the publisher id. A 9-digit id or a
  // stray leading space silently never fills, with nothing logged anywhere,
  // which is exactly how the previous typos went unnoticed.
  slot = typeof slot === "string" ? slot.trim() : slot;
  var slotOk = typeof slot === "string" && /^\d{10}$/.test(slot);

  if (cfg.enabled === true && !slotOk) {
    console.warn(
      "[Breeze/Ads] Slot " + idx + ' is "' + slot + '", which is not a ' +
      "10-digit AdSense unit id. Showing a placeholder instead. " +
      "Fix it in public/ads-config.js."
    );
  }

  var ready =
    cfg.enabled === true &&
    /^ca-pub-\d{10,}$/.test(cfg.client || "") &&
    slotOk &&
    !/AUTO_REPLACE/.test(slot);

  if (!ready) {
    placeholder();
    return;
  }

  // Load the AdSense library for this publisher, then request the ad.
  var s = document.createElement("script");
  s.async = true;
  s.crossOrigin = "anonymous";
  s.src =
    "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=" +
    cfg.client;
  document.head.appendChild(s);

  if (ins) {
    ins.setAttribute("data-ad-client", cfg.client);
    ins.setAttribute("data-ad-slot", slot);
    (window.adsbygoogle = window.adsbygoogle || []).push({});
  }

  // If nothing renders within 3s (no fill / blocked), show the placeholder.
  setTimeout(function () {
    if (ins && ins.offsetHeight < 10) placeholder();
  }, 3000);
})();
