#!/usr/bin/env node
'use strict';
/**
 * Measure every cosmetic uploaded before 1.0.22, in place.
 *
 * Reads each cosmetic's stored model, checks and measures it the way a new
 * upload is (src/cosmeticBackfill.js), and writes the measured GLB and
 * metadata. A .gltf becomes a .glb beside it and the row points at the .glb;
 * a .glb is replaced after its original is kept as model.original.glb.
 * Cosmetics that cannot be measured (a .gltf whose files were never stored)
 * are listed with the reason and left alone; they need uploading again.
 *
 * Run on the API server, from breeze-api/breeze-api, with the API's own env:
 *   node scripts/backfill-cosmetics.cjs            # report only, changes nothing
 *   node scripts/backfill-cosmetics.cjs --apply    # write files and rows
 */

const fs = require('fs');
const path = require('path');

// The same env loading as server.js, before anything reads process.env.
{
    const dotenv = require('dotenv');
    const root = path.join(__dirname, '..');
    const found = ['.env', '.env.local', 'env', '.env.production'].map((n) => path.join(root, n)).find((p) => fs.existsSync(p));
    if (found) dotenv.config({ path: found });
    else dotenv.config();
}

const { createClient } = require('@supabase/supabase-js');
const assets = require('../src/assets');
const { planBackfill } = require('../src/cosmeticBackfill');

async function main() {
    const apply = process.argv.includes('--apply');
    // Deployments name the service role key either way.
    const serviceKey = process.env.SUPABASE_KEY || process.env.SUPABASE_SERVICE_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY;
    if (!process.env.SUPABASE_URL || !serviceKey) {
        console.error('SUPABASE_URL and SUPABASE_KEY (or SUPABASE_SERVICE_KEY) must be set (the API env file).');
        process.exit(1);
    }
    const supabase = createClient(process.env.SUPABASE_URL, serviceKey);
    const { data: rows, error } = await supabase
        .from('cosmetics')
        .select('id, name, slot, model_url, idle_animation, random_animations, metadata');
    if (error) {
        console.error(`Could not read cosmetics: ${error.message}`);
        process.exit(1);
    }

    const counts = { measured: 0, skipped: 0, failed: 0 };
    for (const row of rows || []) {
        const plan = await planBackfill(row, { readAsset: assets.readAsset });
        console.log(`${plan.status.padEnd(8)} ${String(row.id).slice(0, 8)}  ${row.name}: ${plan.reason}`);
        if (plan.status !== 'measured' || !apply) { counts[plan.status]++; continue; }
        for (const file of plan.files) assets.storeAsset(file.rel, file.bytes);
        const { error: upErr } = await supabase.from('cosmetics').update(plan.update).eq('id', row.id);
        if (upErr) {
            // The file is written but the row still points at the old shape, so
            // this is a failure, not a measurement.
            counts.failed++;
            console.error(`         could not update the row: ${upErr.message}`);
            continue;
        }
        counts.measured++;
    }
    console.log(`\n${counts.measured} measured, ${counts.skipped} skipped, ${counts.failed} failed.`);
    if (!apply && counts.measured) console.log('Nothing was changed. Run again with --apply to write them.');
    // A failure has to be visible to whatever ran this.
    if (counts.failed) process.exitCode = 1;
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});
