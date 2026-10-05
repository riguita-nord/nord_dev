local validated = {}
local sessions = {}

local function log(msg)
    if Config.Debug then print(('[Nord Forge] %s'):format(msg)) end
end

local function clean(v)
    return tostring(v or ''):gsub('^%s+',''):gsub('%s+$','')
end

local function forgeKey()
    return clean(GetConvar(Config.ForgeKeyConvar or 'nord_license', ''))
end

local function keymaster()
    local key = clean(GetConvar(Config.KeymasterConvar or 'nord_keymaster', ''))
    if key == '' then key = clean(GetConvar('sv_licenseKey', '')) end
    if key == '' then key = clean(GetConvar('sv_licensekey', '')) end
    return key
end

local function api(path, payload, cb)
    local base = (Config.ForgeApi or ''):gsub('/$', '')
    PerformHttpRequest(base .. path, function(status, body)
        local ok, data = pcall(json.decode, body or '{}')
        cb(status, ok and data or {})
    end, 'POST', json.encode(payload), {['Content-Type']='application/json'})
end

local function payload(product)
    return {
        forge_key = forgeKey(),
        product = product,
        product_slug = product,
        server_id = clean(GetConvar('endpoint_add_tcp', '')) ~= '' and clean(GetConvar('endpoint_add_tcp', '')) or keymaster(),
        server_ip = clean(GetConvar('endpoint_add_tcp', '')),
        keymaster = keymaster(),
        resource_name = GetCurrentResourceName()
    }
end

local function validateProduct(product, cb)
    if forgeKey() == '' then cb(false, 'missing_forge_key') return end
    api('/api/license/validate', payload(product), function(status, data)
        local valid = status == 200 and data.valid == true
        validated[product] = valid
        log(valid and ('Validated '..product) or ('Blocked '..product..': '..tostring(data.error or status)))
        cb(valid, valid and data or (data.error or ('http_'..status)))
    end)
end

local function createSession(product, buildId, cb)
    local p = payload(product); p.build_id = buildId or ''
    api('/api/license/session', p, function(status, data)
        if status == 200 and data.valid and data.decrypt_key then
            sessions[product] = {decrypt_key=data.decrypt_key, expires_at=os.time()+tonumber(data.ttl_seconds or 360)}
            cb(true, data)
        else
            sessions[product] = nil
            cb(false, data.error or ('http_'..status))
        end
    end)
end

exports('ValidateProduct', validateProduct)
exports('CreateSession', createSession)
exports('IsValidated', function(product) return validated[product] == true end)
exports('GetSessionKey', function(product)
    local s=sessions[product]
    if not s or s.expires_at < os.time() then return nil end
    return s.decrypt_key
end)

RegisterCommand('nordforge_check', function(source,args)
    if source ~= 0 then return end
    validateProduct(args[1] or GetCurrentResourceName(), function(ok,result)
        print(('[Nord Forge] %s: %s'):format(ok and 'OK' or 'FAILED', type(result)=='table' and json.encode(result) or tostring(result)))
    end)
end, true)
