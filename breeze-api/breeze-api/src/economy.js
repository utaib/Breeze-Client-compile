'use strict';

/**
 * Breeze Economy, Wind Charges & Breeze Rods
 * ============================================
 * Users never buy items with money directly. They buy WIND CHARGES
 * (the user-facing currency) through PayPal, then spend Wind Charges on
 * capes, cosmetics, and creator content.
 *
 *   $1  = 64 Wind Charges          (a stack of charges per dollar)
 *   1 Breeze Rod = 64 Wind Charges (creator-side earnings unit, $1)
 *
 * Creators never hold Wind Charges. When their item sells, the creator
 * share of the item's Wind Charge price is credited to their
 * `earned_wind_charges` balance, which dashboards display as Breeze Rods
 * (earned_wind_charges / 64). Breeze Rods cannot be bought, spent, or
 * transferred, they exist only to track what the owner owes each
 * creator. Creators request withdrawals; owners review and pay manually
 * through PayPal, then mark the request paid (which deducts the rods).
 *
 * All balances are stored as INTEGER wind charges, no floating point.
 *
 * Supabase columns/tables used (see schema.sql):
 *   users.wind_charges          INTEGER, spendable balance
 *   users.earned_wind_charges   INTEGER, creator earnings (rods * 64)
 *   orders.order_type/'wind_charges', orders.wind_charges
 *   wallet_transactions, audit trail for every balance change
 *   withdrawal_requests, creator payout queue
 */

const WC_PER_USD = 64;
const WC_PER_ROD = 64;
const MIN_WITHDRAWAL_RODS = 5; // $5 minimum payout request

/** Wind Charge packs available for purchase. Bonuses are intentionally
 *  modest: the pack price is the real price, not a psychology trick. */
const WC_PACKS = [
    { id: 'breeze-320', usd: 5, wc: 320, bonus: 0 },
    { id: 'breeze-640', usd: 10, wc: 640, bonus: 32, popular: true },
    { id: 'breeze-1600', usd: 25, wc: 1600, bonus: 128 },
    { id: 'breeze-3200', usd: 50, wc: 3200, bonus: 320 },
];

function priceInWc(priceUsd) {
    return Math.max(0, Math.round(parseFloat(priceUsd || 0) * WC_PER_USD));
}

module.exports = function registerEconomy(app, ctx) {
    const {
        supabase, requireAuth, requireRole, ROLES, CREATOR_ACCESS_ROLES, hasCreatorPasses, ok, fail, log,
        dbGetUser, createNotification, sendBreezeEmail, sendSystemEmail,
        getUserEmail, paypalCreateOrder, purchaseLimiter,
        grantItemToUser, expirePromoIfOneUse,
        findUserByNameOrUuid, lookupFailure, publicUser, featureDisabled,
        CREATOR_SHARE_PERCENT, EMAIL_ADMIN, REPORT_EMAIL,
    } = ctx;

    const { v4: uuidv4 } = require('uuid');

    // Latched false the first time the DB tells us users.creator_passes is
    // missing, so an un-migrated deployment logs one warning instead of
    // retrying a doomed UPDATE on every wallet poll.
    let passColumnExists = true;

    if (purchaseLimiter) app.use('/store/', purchaseLimiter);
    if (purchaseLimiter) app.use('/wallet/purchase', purchaseLimiter);

    /** Item names come from creators; they go into email HTML escaped. */
    function escapeHtml(s) {
        return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    /** Optimistically adjust a user's integer balance column. Retries once on
     *  concurrent modification (Supabase REST has no transactions). Returns the
     *  new balance or null on failure/insufficient funds. */
    async function adjustBalance(uuid, column, delta, { allowNegative = false } = {}) {
        for (let attempt = 0; attempt < 2; attempt++) {
            const { data: row, error } = await supabase
                .from('users')
                .select(`uuid, ${column}`)
                .eq('uuid', uuid)
                .single();
            if (error || !row) return null;
            const current = Number(row[column] || 0);
            const next = current + delta;
            if (next < 0 && !allowNegative) return null;
            const clamped = Math.max(0, next);
            const { data: updated, error: upErr } = await supabase
                .from('users')
                .update({ [column]: clamped })
                .eq('uuid', uuid)
                .eq(column, current)
                .select(`uuid, ${column}`);
            if (!upErr && updated && updated.length) return clamped;
        }
        return null;
    }

    async function recordTransaction(userUuid, type, amountWc, balanceAfter, ref, note) {
        const { error } = await supabase.from('wallet_transactions').insert({
            user_uuid: userUuid,
            type,
            amount_wc: amountWc,
            balance_after: balanceAfter,
            ref: ref || null,
            note: note || null,
            created_at: new Date().toISOString(),
        });
        if (error) log.warn('Wallet', 'Transaction audit skipped', { msg: error.message });
    }

    /** Credit purchased Wind Charges, called by the PayPal webhook when a
     *  wind-charge order captures, and by the owner test bypass. */
    async function creditWindCharges(userUuid, amountWc, ref, note) {
        const newBal = await adjustBalance(userUuid, 'wind_charges', amountWc);
        if (newBal === null) return false;
        await recordTransaction(userUuid, 'purchase', amountWc, newBal, ref, note);
        return newBal;
    }

    /** Deduct Wind Charges on refund. Floors at zero (the launcher revokes
     *  nothing retroactively; abuse is an owner-side judgement call). */
    async function debitWindChargesForRefund(userUuid, amountWc, ref) {
        const newBal = await adjustBalance(userUuid, 'wind_charges', -amountWc, { allowNegative: false })
            ?? await adjustBalance(userUuid, 'wind_charges', 0); // balance below refund → floor
        if (newBal === null) return false;
        await recordTransaction(userUuid, 'refund', -amountWc, newBal, ref, 'PayPal refund');
        return newBal;
    }

    ctx.economy = { WC_PER_USD, WC_PER_ROD, priceInWc, creditWindCharges, debitWindChargesForRefund };

    /* ─────────────────────────── Wallet ─────────────────────────── */

    app.get('/wallet/packs', (req, res) => {
        return ok(res, {
            wc_per_usd: WC_PER_USD,
            wc_per_rod: WC_PER_ROD,
            packs: WC_PACKS.map((p) => ({ ...p, total_wc: p.wc + p.bonus })),
        });
    });

    app.get('/wallet', requireAuth, async (req, res) => {
        try {
            const user = await dbGetUser(req.user.uuid);
            if (!user) return fail(res, 'User not found', 404);
            const { data: txns } = await supabase
                .from('wallet_transactions')
                .select('type, amount_wc, balance_after, note, created_at')
                .eq('user_uuid', req.user.uuid)
                .order('created_at', { ascending: false })
                .limit(25);
            const holdsPasses = hasCreatorPasses(user.role);
            let passes = Number(user.creator_passes || 0);

            // Self-heal: an owner/admin who predates Creator Passes (or whose
            // deployment hasn't run the schema.sql backfill) sits at 0 forever
            // with no route to a grant. Issue the standard grant once, only if
            // they have never spent a pass, so a legitimately drained balance is
            // never silently refilled.
            if (holdsPasses && passes === 0 && passColumnExists) {
                const spentBefore = (txns ?? []).some((t) => t.type === 'pass-spend');
                if (!spentBefore) {
                    const { count } = await supabase
                        .from('wallet_transactions')
                        .select('id', { count: 'exact', head: true })
                        .eq('user_uuid', req.user.uuid)
                        .eq('type', 'pass-spend');
                    if (!count) {
                        const grant = parseInt(process.env.CREATOR_PASS_GRANT ?? '6400', 10);
                        const { error: grantErr } = await supabase
                            .from('users').update({ creator_passes: grant }).eq('uuid', req.user.uuid);
                        if (grantErr) {
                            // The wallet is polled every 30s per client, latch
                            // the missing column so one migration warning is
                            // logged instead of a permanent retry storm.
                            if (/creator_passes.*does not exist/i.test(grantErr.message)) {
                                passColumnExists = false;
                                log.warn('Wallet', 'creator_passes column missing, run schema.sql to enable Creator Passes');
                            } else {
                                log.warn('Wallet', `creator_passes not granted: ${grantErr.message}`);
                            }
                        } else {
                            passes = grant;
                            log.info('Wallet', `Creator Passes granted (${grant}) to ${user.role} ${req.user.uuid}`);
                        }
                    }
                }
            }

            return ok(res, {
                wind_charges: Number(user.wind_charges || 0),
                creator_passes: holdsPasses ? passes : 0,
                is_creator: holdsPasses,
                wc_per_usd: WC_PER_USD,
                transactions: txns ?? [],
            });
        } catch (err) {
            log.error('Wallet', 'Balance error', { msg: err.message });
            return fail(res, 'Server error', 500);
        }
    });

    /** Start a Wind Charge purchase → PayPal order. The webhook credits the
     *  charges when PAYMENT.CAPTURE.COMPLETED arrives. */
    app.post('/wallet/purchase', requireAuth, async (req, res) => {
        const CTX = 'Wallet/Purchase';
        try {
            const pack = WC_PACKS.find((p) => p.id === String(req.body.pack_id || ''));
            if (!pack) return fail(res, 'Unknown Wind Charge pack');
            const totalWc = pack.wc + pack.bonus;
            const user = await dbGetUser(req.user.uuid);
            if (!user) return fail(res, 'User not found', 404);

            // Owner bypass keeps end-to-end testing possible without live money.
            if (user.role === ROLES.OWNER) {
                const bal = await creditWindCharges(req.user.uuid, totalWc, `owner-test`, `${pack.id} (owner bypass)`);
                return ok(res, { granted: true, wind_charges: bal, message: 'Wind Charges credited (owner bypass)' });
            }

            const orderId = uuidv4();
            const { error: insErr } = await supabase.from('orders').insert({
                id: orderId,
                user_uuid: req.user.uuid,
                order_type: 'wind_charges',
                wind_charges: totalWc,
                gross_amount_usd: pack.usd,
                status: 'pending',
                created_at: new Date().toISOString(),
            });
            if (insErr) {
                log.error(CTX, 'Order insert error', { msg: insErr.message });
                return fail(res, 'Failed to create order', 500);
            }
            const paypalOrder = await paypalCreateOrder(
                pack.usd,
                orderId,
                `Breeze Wind Charges, ${totalWc} charges`,
            );
            await supabase.from('orders').update({ paypal_order_id: paypalOrder.id }).eq('id', orderId);
            log.info(CTX, `WC order ${orderId}: ${totalWc} WC / $${pack.usd} for ${req.user.uuid}`);
            return ok(res, { order_id: orderId, approve_url: paypalOrder.approve_url, amount_usd: pack.usd, wind_charges: totalWc }, 201);
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Failed to start Wind Charge purchase', 500);
        }
    });

    /* ─────────────────────── Spending charges ───────────────────── */

    /** Buy a cape or cosmetic with Wind Charges. Item prices remain stored in
     *  USD (price_usd) and convert at 64 WC/$. Creator share is credited to the
     *  creator's earned_wind_charges (their Breeze Rods). */
    // One purchase at a time per account: a double click used to send two
    // requests that both passed the ownership check and both charged.
    const purchasesInFlight = new Set();

    app.post('/store/purchase-item', requireAuth, async (req, res) => {
        const CTX = 'Store/PurchaseItem';
        const lockKey = req.user.uuid;
        if (purchasesInFlight.has(lockKey)) return fail(res, 'A purchase is already in progress. Please wait a moment.', 409);
        purchasesInFlight.add(lockKey);
        try {
            const { cape_id, cosmetic_id, promo_code } = req.body;
            if (!cape_id && !cosmetic_id) return fail(res, 'cape_id or cosmetic_id is required');
            if (cape_id && cosmetic_id) return fail(res, 'Provide either cape_id or cosmetic_id, not both');
            const isCosmetic = Boolean(cosmetic_id);
            const itemId = isCosmetic ? cosmetic_id : cape_id;
            const itemLabel = isCosmetic ? 'cosmetic' : 'cape';
            const grantIds = isCosmetic ? { cosmeticId: itemId } : { capeId: itemId };

            const buyer = await dbGetUser(req.user.uuid);
            if (!buyer) return fail(res, 'User not found', 404);

            // Buying for someone else. The buyer pays, the creator earns the
            // same way, and the item is delivered to the recipient: a gift is a
            // purchase with a different destination, not a free copy.
            const giftTo = req.body.gift_to ?? req.body.recipient_username ?? req.body.recipient_uuid;
            let recipient = null;
            if (giftTo) {
                if (await featureDisabled('gifting_disabled', buyer)) {
                    return fail(res, 'Gifting is unavailable right now', 503);
                }
                const found = await findUserByNameOrUuid({
                    uuid: req.body.recipient_uuid ?? (typeof giftTo === 'string' ? giftTo : null),
                    username: req.body.recipient_username ?? (typeof giftTo === 'string' ? giftTo : null),
                });
                if (!found.user) return lookupFailure(res, found.reason);
                if (found.user.uuid === buyer.uuid) return fail(res, 'You already own what you buy for yourself');
                recipient = found.user;
            }
            const beneficiary = recipient || buyer;

            // Ownership check, against whoever receives the item.
            const ownTable = isCosmetic ? 'user_cosmetics' : 'user_capes';
            const ownCol = isCosmetic ? 'cosmetic_id' : 'cape_id';
            const { data: alreadyOwned } = await supabase
                .from(ownTable).select(ownCol)
                .eq('user_uuid', beneficiary.uuid).eq(ownCol, itemId).maybeSingle();
            if (alreadyOwned) {
                return fail(res, recipient
                    ? `${recipient.username} already owns this ${itemLabel}`
                    : `You already own this ${itemLabel}`);
            }

            const { data: item, error: itemErr } = await supabase
                .from(isCosmetic ? 'cosmetics' : 'capes')
                .select('id, name, price_usd, creator_id, is_public')
                .eq('id', itemId).single();
            if (itemErr || !item) return fail(res, `${itemLabel} not found`, 404);
            if (!item.is_public && buyer.role !== ROLES.OWNER) {
                return fail(res, `This ${itemLabel} is not available`, 403);
            }

            // Currency: real Wind Charges (default) or Creator Passes, a
            // staff/creator TEST currency that spends like WC but never generates
            // earnings, commission, transactions or payout records for the seller.
            // Owners, admins and creators hold passes; nobody else does.
            const currency =
                String(req.body.currency || '').toLowerCase() === 'creator_passes'
                    ? 'creator_passes'
                    : 'wind_charges';
            if (currency === 'creator_passes' && !hasCreatorPasses(buyer.role)) {
                return fail(res, 'Creator Passes are only available to owners, admins, developers and creators', 403);
            }
            const balCol = currency === 'creator_passes' ? 'creator_passes' : 'wind_charges';
            const curLabel = currency === 'creator_passes' ? 'Creator Passes' : 'Wind Charges';

            // Owner bypass, free grant for moderation/testing. A Creator Pass
            // purchase IS the test, so pass buys fall through to the real
            // debit path below; otherwise an owner's passes would never move
            // and the test currency would prove nothing.
            if (buyer.role === ROLES.OWNER && currency !== 'creator_passes') {
                const ownerGrantErr = await grantItemToUser(beneficiary.uuid, grantIds);
                if (ownerGrantErr) {
                    log.error(CTX, 'Owner grant failed', { msg: ownerGrantErr.message });
                    return fail(res, `Failed to deliver ${itemLabel}`, 500);
                }
                if (recipient) await deliverGift({ buyer, recipient, item, itemId, isCosmetic, costWc: 0 });
                return ok(res, {
                    granted: true,
                    recipient: recipient ? publicUser(recipient) : null,
                    currency: 'wind_charges',
                    spent: 0,
                    spent_wc: 0,
                    balance: Number(buyer.wind_charges || 0),
                    wind_charges: Number(buyer.wind_charges || 0),
                    creator_passes: Number(buyer.creator_passes || 0),
                    discount_percent: 0,
                    message: `${itemLabel} granted (owner bypass)`,
                });
            }

            let costWc = priceInWc(item.price_usd);
            let appliedPromoId = null;
            let discountPct = 0;

            if (promo_code && costWc > 0) {
                const { data: promo } = await supabase
                    .from('promo_codes')
                    .select('id, code, discount_percent, usage_limit, times_used, is_active')
                    .eq('code', String(promo_code).toUpperCase().trim())
                    .maybeSingle();
                if (!promo || !promo.is_active) return fail(res, 'Invalid promo code');
                if (promo.usage_limit !== null && promo.times_used >= promo.usage_limit)
                    return fail(res, 'This promo code has reached its usage limit');
                const { data: used } = await supabase
                    .from('promo_code_uses').select('id')
                    .eq('promo_code_id', promo.id).eq('user_uuid', req.user.uuid).maybeSingle();
                if (used) return fail(res, 'You have already used this promo code');
                discountPct = promo.discount_percent;
                costWc = Math.max(0, Math.round(costWc * (1 - discountPct / 100)));
                appliedPromoId = promo.id;
            }

            // Deduct from the chosen balance (free items skip the wallet entirely)
            let newBalance = Number(buyer[balCol] || 0);
            if (costWc > 0) {
                const bal = await adjustBalance(req.user.uuid, balCol, -costWc);
                if (bal === null) {
                    return fail(res, `Not enough ${curLabel}, this ${itemLabel} costs ${costWc.toLocaleString()} and you have ${Number(buyer[balCol] || 0).toLocaleString()}.`, 402);
                }
                newBalance = bal;
            }

            const grantErr = await grantItemToUser(beneficiary.uuid, grantIds);
            if (grantErr) {
                // Grant failed after deduction: refund immediately, same currency.
                if (costWc > 0) await adjustBalance(req.user.uuid, balCol, costWc);
                log.error(CTX, 'Grant failed, charges refunded', { msg: grantErr.message });
                return fail(res, `Failed to deliver ${itemLabel}`, 500);
            }

            // Pass spends are logged distinctly so financial reports (type='earn'
            // / 'spend' on real WC) never pick them up.
            await recordTransaction(
                req.user.uuid,
                currency === 'creator_passes' ? 'pass-spend' : 'spend',
                -costWc,
                newBalance,
                `${itemLabel}:${itemId}`,
                currency === 'creator_passes' ? `${item.name} (Creator Pass test)` : item.name,
            );

            if (appliedPromoId) {
                await supabase.from('promo_code_uses').insert({ promo_code_id: appliedPromoId, user_uuid: req.user.uuid });
                await supabase.rpc('increment_promo_uses', { promo_id: appliedPromoId }).then(() => {});
                if (expirePromoIfOneUse) await expirePromoIfOneUse(appliedPromoId);
            }

            // Creator earnings → Breeze Rods (share % of the WC actually paid).
            // Creator Passes are a test currency: they NEVER generate earnings,
            // commission, an 'earn' transaction, or a seller notification.
            if (currency === 'wind_charges' && item.creator_id && costWc > 0) {
                const { data: creatorRow } = await supabase
                    .from('users').select('creator_share_percent')
                    .eq('uuid', item.creator_id).maybeSingle();
                const sharePct = creatorRow?.creator_share_percent != null
                    ? parseFloat(creatorRow.creator_share_percent)
                    : parseFloat(CREATOR_SHARE_PERCENT || 70);
                const earnedWc = Math.round(costWc * (sharePct / 100));
                if (earnedWc > 0) {
                    const creatorBal = await adjustBalance(item.creator_id, 'earned_wind_charges', earnedWc);
                    if (creatorBal !== null) {
                        await recordTransaction(item.creator_id, 'earn', earnedWc, creatorBal, `${itemLabel}:${itemId}`, `Sale: ${item.name}`);
                        await createNotification(
                            item.creator_id, 'earning', 'Your item sold',
                            `${buyer.username} bought ${item.name}, ${(earnedWc / WC_PER_ROD).toFixed(2)} Breeze Rods earned.`,
                            { item_id: itemId, earned_wc: earnedWc },
                        );
                        const creatorEmail = await getUserEmail(item.creator_id);
                        await sendBreezeEmail(
                            creatorEmail, 'Your Breeze item sold', 'You earned Breeze Rods',
                            `<p><strong>${escapeHtml(item.name)}</strong> was purchased. <strong>${(earnedWc / WC_PER_ROD).toFixed(2)} Breeze Rods</strong> were added to your creator balance. Request a withdrawal from your creator dashboard whenever you're ready.</p>`,
                        );
                    }
                }
            }

            if (recipient) {
                await deliverGift({ buyer, recipient, item, itemId, isCosmetic, costWc });
            } else {
                await createNotification(
                    req.user.uuid, 'purchase', 'Purchase complete',
                    `${item.name} is now in your inventory.`,
                    { item_id: itemId, spent_wc: costWc },
                );
            }

            log.info(CTX, `${itemLabel} ${itemId} → ${beneficiary.uuid}${recipient ? ` (gift from ${buyer.uuid})` : ''} for ${costWc} ${currency}${currency === 'creator_passes' ? ' (test, no earnings)' : ''}`);
            return ok(res, {
                granted: true,
                recipient: recipient ? publicUser(recipient) : null,
                currency,
                spent: costWc,
                spent_wc: currency === 'wind_charges' ? costWc : 0,
                balance: newBalance,
                wind_charges: currency === 'wind_charges' ? newBalance : Number(buyer.wind_charges || 0),
                creator_passes: currency === 'creator_passes' ? newBalance : Number(buyer.creator_passes || 0),
                discount_percent: discountPct,
            });
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Purchase failed', 500);
        } finally {
            purchasesInFlight.delete(lockKey);
        }
    });

    /**
     * Record a bought gift and tell both people about it.
     *
     * The gifts row is what the launcher opens as an unwrapped present, so it
     * matters as much as the inventory row. A failure is logged loudly rather
     * than swallowed: the item has already been delivered by this point.
     */
    async function deliverGift({ buyer, recipient, item, itemId, isCosmetic, costWc }) {
        const { error } = await supabase.from('gifts').insert({
            sender_uuid: buyer.uuid,
            recipient_uuid: recipient.uuid,
            cape_id: isCosmetic ? null : itemId,
            cosmetic_id: isCosmetic ? itemId : null,
            source: 'purchase_gift',
            status: 'delivered',
            created_at: new Date().toISOString(),
        });
        if (error) log.error('Store/Gift', 'Gift record failed', { msg: error.message });
        await createNotification(
            recipient.uuid, 'gift', 'You received a Breeze gift',
            `${buyer.username} bought you ${item.name}.`,
            { cape_id: isCosmetic ? null : itemId, cosmetic_id: isCosmetic ? itemId : null },
        );
        await createNotification(
            buyer.uuid, 'gift_sent', 'Gift delivered',
            `${item.name} was bought for ${recipient.username}.`,
            { recipient_uuid: recipient.uuid, item_id: itemId, spent_wc: costWc },
        );
    }

    /** Personal cape, paid in Wind Charges (replaces the legacy $20 PayPal
     *  flow). $20 = 1280 WC. The cape applies instantly on payment, no
     *  webhook wait, no pending stash. */
    const PERSONAL_CAPE_WC = priceInWc(parseFloat(process.env.PERSONAL_CAPE_PRICE_USD ?? '20'));
    app.post('/capes/personal-wc', requireAuth, ctx.upload.single('cape'), async (req, res) => {
        const CTX = 'Capes/PersonalWC';
        try {
            if (!req.file) return fail(res, 'No image provided');
            if (!['image/png', 'image/jpeg', 'image/jpg'].includes(req.file.mimetype)) {
                return fail(res, 'Only PNG and JPEG images are accepted');
            }
            const buyer = await dbGetUser(req.user.uuid);
            if (!buyer) return fail(res, 'User not found', 404);
            const fitted = await ctx.fitCapeBuffer(req.file.buffer);

            let costWc = buyer.role === ROLES.OWNER ? 0 : PERSONAL_CAPE_WC;
            let newBalance = Number(buyer.wind_charges || 0);
            if (costWc > 0) {
                const bal = await adjustBalance(req.user.uuid, 'wind_charges', -costWc);
                if (bal === null) {
                    return fail(res, `A personal cape costs ${costWc.toLocaleString()} Wind Charges, you have ${newBalance.toLocaleString()}.`, 402);
                }
                newBalance = bal;
            }

            const assets = require('./assets');
            const fileRel = `capes/personal/${req.user.uuid}_cape.png`;
            try {
                assets.storeAsset(fileRel, fitted.buffer);
            } catch (storeErr) {
                if (costWc > 0) await adjustBalance(req.user.uuid, 'wind_charges', costWc);
                log.error(CTX, 'Store failed, charges refunded', { msg: storeErr.message });
                return fail(res, 'Failed to store cape', 500);
            }
            const capeUrl = assets.assetUrl(ctx.getRequestBaseUrl(req), fileRel);
            await supabase.from('users').update({ cape_url: capeUrl }).eq('uuid', req.user.uuid);

            if (costWc > 0) {
                await recordTransaction(req.user.uuid, 'spend', -costWc, newBalance, 'personal-cape', 'Personal cape');
            }
            await createNotification(
                req.user.uuid, 'purchase', 'Personal cape applied',
                'Your personal cape is live on your account.',
                { spent_wc: costWc },
            );
            log.info(CTX, `Personal cape applied for ${req.user.uuid} (${costWc} WC)`);
            return ok(res, { granted: true, cape_url: capeUrl, spent_wc: costWc, wind_charges: newBalance });
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Personal cape purchase failed', 500);
        }
    });

    /* ─────────────────── Creator earnings & payouts ─────────────── */

    app.get('/creator/wallet', requireAuth, requireRole(...CREATOR_ACCESS_ROLES), async (req, res) => {
        try {
            const user = await dbGetUser(req.user.uuid);
            if (!user) return fail(res, 'User not found', 404);
            const earnedWc = Number(user.earned_wind_charges || 0);
            const { data: withdrawals } = await supabase
                .from('withdrawal_requests')
                .select('id, amount_wc, status, note, admin_note, created_at, resolved_at')
                .eq('user_uuid', req.user.uuid)
                .order('created_at', { ascending: false })
                .limit(20);
            const { data: earns } = await supabase
                .from('wallet_transactions')
                .select('amount_wc, note, created_at')
                .eq('user_uuid', req.user.uuid)
                .eq('type', 'earn')
                .order('created_at', { ascending: false })
                .limit(25);
            return ok(res, {
                earned_wind_charges: earnedWc,
                breeze_rods: earnedWc / WC_PER_ROD,
                wc_per_rod: WC_PER_ROD,
                min_withdrawal_rods: MIN_WITHDRAWAL_RODS,
                creator_passes: Number(user.creator_passes || 0),
                withdrawals: withdrawals ?? [],
                recent_earnings: earns ?? [],
            });
        } catch (err) {
            log.error('Creator/Wallet', 'Error', { msg: err.message });
            return fail(res, 'Server error', 500);
        }
    });

    app.post('/creator/withdrawals', requireAuth, requireRole(...CREATOR_ACCESS_ROLES), async (req, res) => {
        const CTX = 'Creator/Withdraw';
        try {
            const user = await dbGetUser(req.user.uuid);
            if (!user) return fail(res, 'User not found', 404);
            const earnedWc = Number(user.earned_wind_charges || 0);
            const requestedRods = parseFloat(req.body.amount_rods);
            if (!Number.isFinite(requestedRods) || requestedRods < MIN_WITHDRAWAL_RODS) {
                return fail(res, `Minimum withdrawal is ${MIN_WITHDRAWAL_RODS} Breeze Rods ($${MIN_WITHDRAWAL_RODS}).`);
            }
            const amountWc = Math.round(requestedRods * WC_PER_ROD);
            if (amountWc > earnedWc) {
                return fail(res, `You only have ${(earnedWc / WC_PER_ROD).toFixed(2)} Breeze Rods available.`);
            }
            const { data: existing } = await supabase
                .from('withdrawal_requests').select('id')
                .eq('user_uuid', req.user.uuid).eq('status', 'pending').maybeSingle();
            if (existing) return fail(res, 'You already have a pending withdrawal request.');

            const paypalEmail = String(req.body.paypal_email || user.paypal_email || '').trim();
            if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(paypalEmail)) {
                return fail(res, 'A valid PayPal email is required for payout.');
            }
            if (paypalEmail !== user.paypal_email) {
                await supabase.from('users').update({ paypal_email: paypalEmail }).eq('uuid', req.user.uuid);
            }

            const { data: request, error } = await supabase
                .from('withdrawal_requests')
                .insert({
                    user_uuid: req.user.uuid,
                    amount_wc: amountWc,
                    paypal_email: paypalEmail,
                    status: 'pending',
                    note: String(req.body.note || '').slice(0, 300) || null,
                    created_at: new Date().toISOString(),
                })
                .select().single();
            if (error) {
                log.error(CTX, 'Insert error', { msg: error.message });
                return fail(res, 'Failed to submit withdrawal request', 500);
            }

            // Notify the owner through email + admin notifications
            await sendSystemEmail({
                to: REPORT_EMAIL,
                from: EMAIL_ADMIN,
                subject: `Breeze withdrawal request, ${user.username} ($${(amountWc / WC_PER_ROD).toFixed(2)})`,
                text: [
                    `Creator: ${user.username} (${user.uuid})`,
                    `Amount: ${(amountWc / WC_PER_ROD).toFixed(2)} Breeze Rods ($${(amountWc / WC_PER_ROD).toFixed(2)})`,
                    `PayPal: ${paypalEmail}`,
                    `Note: ${req.body.note || '(none)'}`,
                    ``,
                    `Review it in the admin panel, pay through PayPal, then mark it paid.`,
                ].join('\n'),
            });
            await createNotification(
                req.user.uuid, 'withdrawal', 'Withdrawal request submitted',
                `Your request for ${(amountWc / WC_PER_ROD).toFixed(2)} Breeze Rods is pending owner review.`,
                { request_id: request.id },
            );
            log.info(CTX, `${user.username} requested ${(amountWc / WC_PER_ROD).toFixed(2)} rods → ${paypalEmail}`);
            return ok(res, { request }, 201);
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Server error', 500);
        }
    });

    /* ───────────────────── Owner payout management ──────────────── */

    app.get('/admin/withdrawals', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
        try {
            const { data: rows } = await supabase
                .from('withdrawal_requests')
                .select('*')
                .order('created_at', { ascending: false })
                .limit(100);
            const uuids = [...new Set((rows ?? []).map((r) => r.user_uuid))];
            let names = new Map();
            if (uuids.length) {
                const { data: users } = await supabase.from('users').select('uuid, username, paypal_email').in('uuid', uuids);
                names = new Map((users ?? []).map((u) => [u.uuid, u]));
            }
            return ok(res, {
                wc_per_rod: WC_PER_ROD,
                withdrawals: (rows ?? []).map((r) => ({
                    ...r,
                    username: names.get(r.user_uuid)?.username || r.user_uuid,
                })),
            });
        } catch (err) {
            log.error('Admin/Withdrawals', 'Error', { msg: err.message });
            return fail(res, 'Server error', 500);
        }
    });

    /** Owner resolves a request: approved (acknowledged), paid (money sent, *  deducts the creator's rods), or rejected. Payment itself happens
     *  manually in PayPal; this endpoint just records the outcome. */
    app.patch('/admin/withdrawals/:id', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
        const CTX = 'Admin/ResolveWithdrawal';
        try {
            const status = String(req.body.status || '');
            if (!['approved', 'paid', 'rejected'].includes(status)) {
                return fail(res, 'status must be approved, paid, or rejected');
            }
            const { data: request } = await supabase
                .from('withdrawal_requests').select('*').eq('id', req.params.id).maybeSingle();
            if (!request) return fail(res, 'Withdrawal request not found', 404);
            if (request.status === 'paid') return fail(res, 'Request is already settled');

            if (status === 'paid') {
                const newBal = await adjustBalance(request.user_uuid, 'earned_wind_charges', -Number(request.amount_wc));
                if (newBal === null) {
                    return fail(res, "Creator's rod balance is below the requested amount, resolve manually.", 409);
                }
                await recordTransaction(request.user_uuid, 'withdrawal', -Number(request.amount_wc), newBal, `withdrawal:${request.id}`, 'Payout completed');
            }

            const { data: updated, error } = await supabase
                .from('withdrawal_requests')
                .update({
                    status,
                    admin_note: String(req.body.admin_note || '').slice(0, 300) || null,
                    resolved_at: new Date().toISOString(),
                })
                .eq('id', req.params.id)
                .select().single();
            if (error) return fail(res, 'Failed to update request', 500);

            const rods = (Number(request.amount_wc) / WC_PER_ROD).toFixed(2);
            const messages = {
                approved: `Your withdrawal of ${rods} Breeze Rods was approved, payment is on its way.`,
                paid: `Your withdrawal of ${rods} Breeze Rods ($${rods}) has been paid to ${request.paypal_email}.`,
                rejected: `Your withdrawal request for ${rods} Breeze Rods was declined.${req.body.admin_note ? ` Reason: ${req.body.admin_note}` : ''}`,
            };
            await createNotification(request.user_uuid, 'withdrawal', 'Withdrawal update', messages[status], { request_id: request.id, status });
            const creatorEmail = await getUserEmail(request.user_uuid);
            await sendBreezeEmail(creatorEmail, 'Breeze withdrawal update', 'Withdrawal update', `<p>${messages[status]}</p>`);
            log.info(CTX, `Withdrawal ${request.id} → ${status}`);
            return ok(res, { request: updated });
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Server error', 500);
        }
    });

    /* ── Monthly creator payout report ───────────────────────────────
       Aggregates every creator's earnings, converts Wind Charges → USD
       (64 WC = 1 Breeze Rod = $1), includes each creator's PayPal email, and
       emails the summary to the owner + co-owner/payout manager so payouts can
       be sent. "Outstanding" is the unpaid balance the manager should pay. */
    async function generateMonthlyCreatorReport(opts = {}) {
        const now = new Date();
        let { year, month } = opts; // month is 1..12
        if (!year || !month) {
            const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
            year = prev.getFullYear();
            month = prev.getMonth() + 1;
        }
        const from = new Date(year, month - 1, 1);
        const to = new Date(year, month, 1);
        const label = from.toLocaleString('en-US', { month: 'long', year: 'numeric' });

        const { data: creators } = await supabase
            .from('users')
            .select('uuid, username, paypal_email, earned_wind_charges')
            // Developers upload under creator rules and earn the same way, so
            // their earnings belong in the payout report too.
            .in('role', [ROLES.CREATOR, ROLES.DEVELOPER]);
        const list = creators || [];

        const { data: txns } = await supabase
            .from('wallet_transactions')
            .select('user_uuid, amount_wc, type, created_at')
            .eq('type', 'earn')
            .gte('created_at', from.toISOString())
            .lt('created_at', to.toISOString());
        const periodBy = {};
        for (const t of txns || []) {
            const k = t.user_uuid;
            if (!periodBy[k]) periodBy[k] = { wc: 0, sales: 0 };
            periodBy[k].wc += Number(t.amount_wc || 0);
            periodBy[k].sales += 1;
        }

        const rows = list
            .map((c) => {
                const outstandingWc = Number(c.earned_wind_charges || 0);
                const p = periodBy[c.uuid] || { wc: 0, sales: 0 };
                return {
                    username: c.username || 'N/A',
                    paypal_email: c.paypal_email || '(not set)',
                    period_sales: p.sales,
                    period_usd: p.wc / WC_PER_USD,
                    outstanding_usd: outstandingWc / WC_PER_USD,
                };
            })
            .sort((a, b) => b.outstanding_usd - a.outstanding_usd);

        const totalOutstanding = rows.reduce((s, r) => s + r.outstanding_usd, 0);
        const totalPeriod = rows.reduce((s, r) => s + r.period_usd, 0);
        return { label, year, month, rows, totalOutstanding, totalPeriod };
    }

    function renderCreatorReportHtml(report) {
        const cell = 'padding:6px 10px;border-bottom:1px solid #e6e9f0';
        const tr = report.rows
            .map(
                (r) => `<tr>
            <td style="${cell}">${r.username}</td>
            <td style="${cell}">${r.paypal_email}</td>
            <td style="${cell};text-align:right">${r.period_sales}</td>
            <td style="${cell};text-align:right">$${r.period_usd.toFixed(2)}</td>
            <td style="${cell};text-align:right"><strong>$${r.outstanding_usd.toFixed(2)}</strong></td>
        </tr>`,
            )
            .join('');
        return `<h2 style="font-family:Arial,sans-serif">Breeze creator payout report, ${report.label}</h2>
            <p style="font-family:Arial,sans-serif;font-size:13px">Pay each creator the <strong>Outstanding</strong> amount to the PayPal email shown. 64 Wind Charges = 1 Breeze Rod = $1.</p>
            <table style="border-collapse:collapse;font-family:Arial,sans-serif;font-size:13px">
              <thead><tr style="background:#f4f6fb">
                <th style="${cell};text-align:left">Creator</th>
                <th style="${cell};text-align:left">PayPal email</th>
                <th style="${cell};text-align:right">Sales</th>
                <th style="${cell};text-align:right">Earned this month</th>
                <th style="${cell};text-align:right">Outstanding (pay this)</th>
              </tr></thead>
              <tbody>${tr || '<tr><td colspan="5" style="' + cell + '">No creators.</td></tr>'}</tbody>
              <tfoot><tr style="background:#f4f6fb;font-weight:bold">
                <td style="${cell}" colspan="3">Total</td>
                <td style="${cell};text-align:right">$${report.totalPeriod.toFixed(2)}</td>
                <td style="${cell};text-align:right">$${report.totalOutstanding.toFixed(2)}</td>
              </tr></tfoot>
            </table>`;
    }

    function reportRecipients() {
        return (
            process.env.PAYOUT_REPORT_RECIPIENTS ||
            [process.env.OWNER_EMAIL, process.env.COOWNER_EMAIL, process.env.PAYOUT_MANAGER_EMAIL]
                .filter(Boolean)
                .join(',') ||
            REPORT_EMAIL
        );
    }

    // Owner: generate + email the monthly creator payout report now. Body may
    // include {month,year}; defaults to the previous calendar month. This is the
    // reliable manual trigger, host schedulers (Pterodactyl) may sleep.
    app.post('/admin/creator-report/run', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
        const CTX = 'Economy/CreatorReport';
        try {
            const report = await generateMonthlyCreatorReport({
                month: req.body?.month ? parseInt(req.body.month, 10) : undefined,
                year: req.body?.year ? parseInt(req.body.year, 10) : undefined,
            });
            const to = reportRecipients();
            await sendSystemEmail({
                to,
                subject: `Breeze creator payout report, ${report.label} ($${report.totalOutstanding.toFixed(2)} to pay)`,
                html: renderCreatorReportHtml(report),
            });
            log.info(CTX, `Report ${report.label} → ${to} (${report.rows.length} creators, $${report.totalOutstanding.toFixed(2)})`);
            return ok(res, { report, sent_to: to });
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Failed to generate report', 500);
        }
    });

    // Owner/admin: preview the report as JSON without emailing.
    app.get('/admin/creator-report', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
        try {
            const report = await generateMonthlyCreatorReport({
                month: req.query?.month ? parseInt(req.query.month, 10) : undefined,
                year: req.query?.year ? parseInt(req.query.year, 10) : undefined,
            });
            return ok(res, { report });
        } catch (err) {
            log.error('Economy/CreatorReport', 'Error', { msg: err.message });
            return fail(res, 'Failed to generate report', 500);
        }
    });
};

module.exports.WC_PER_USD = WC_PER_USD;
module.exports.WC_PER_ROD = WC_PER_ROD;
module.exports.priceInWc = priceInWc;
