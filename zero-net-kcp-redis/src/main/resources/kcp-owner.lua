-- A single hash preserves the generation tombstone after lease expiry.
local key = KEYS[1]
local op = ARGV[1]
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local row = redis.call('HMGET', key, 'owner', 'generation', 'expires', 'phase', 'snapshot')
local owner, gen, expiry, phase = row[1], row[2], tonumber(row[3]) or 0, row[4]
local live = owner and phase ~= 'RELEASED' and expiry > now
-- Never convert 64-bit generations to Lua doubles. Canonical positive decimal strings.
local function greater(a, b)
    return #a > #b or (#a == #b and a > b)
end
local function write(nextOwner, nextGen, nextExpiry, nextPhase, snapshot)
    redis.call('HSET', key, 'owner', nextOwner, 'generation', nextGen,
        'expires', string.format('%.0f', nextExpiry), 'phase', nextPhase)
    if snapshot then redis.call('HSET', key, 'snapshot', snapshot)
    else redis.call('HDEL', key, 'snapshot') end
    redis.call('PEXPIRE', key, math.max(0, nextExpiry - now) + 86400000)
    return 1
end
if op == 'owner' then
    if not live then return {} end
    return {owner, gen, row[3], phase}
elseif op == 'load' then
    if live and phase == 'PENDING' then return row[5] end
    return false
elseif op == 'acquire' then
    if live or (gen and not greater(ARGV[3], gen)) then return 0 end
    return write(ARGV[2], ARGV[3], now + tonumber(ARGV[4]), 'ACTIVE', false)
elseif op == 'release' then
    if owner ~= ARGV[2] or gen ~= ARGV[3] then return 0 end
    return write(owner, gen, now, 'RELEASED', false)
end
if not live or owner ~= ARGV[2] or gen ~= ARGV[3] then return 0 end
if op == 'renew' and (phase == 'ACTIVE' or phase == 'FROZEN') then
    return write(owner, gen, now + tonumber(ARGV[4]), phase, row[5])
elseif op == 'freeze' and phase == 'ACTIVE' then
    return write(owner, gen, expiry, 'FROZEN', row[5])
elseif op == 'unfreeze' and phase == 'FROZEN' then
    return write(owner, gen, expiry, 'ACTIVE', false)
elseif op == 'claim' and phase == 'PENDING' then
    return write(owner, gen, now + tonumber(ARGV[4]), 'ACTIVE', false)
elseif op == 'migrate' and phase == 'FROZEN' and ARGV[5] ~= owner
        and greater(ARGV[6], gen) and tonumber(ARGV[8]) > now then
    return write(ARGV[5], ARGV[6], math.min(now + tonumber(ARGV[4]), tonumber(ARGV[8])), 'PENDING', ARGV[7])
end
return 0
