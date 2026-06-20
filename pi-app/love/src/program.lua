--
-- Modèle du programme : parse le JSON (schéma caen.tech/program/schema.json) et
-- construit les structures dérivées pour les écrans (créneaux, lignes, cartes,
-- sponsors). Déclenche le téléchargement asynchrone des visuels (logo, photos,
-- logos sponsors) via `fetch`.
--
local json  = require("src.json")
local fetch = require("src.fetch")

local program = {}

program.data    = nil
program.status  = { state = "idle", message = "Aucun programme chargé", sessions = 0 }
program.assets  = { logoMain = nil, speaker = {}, sponsor = {} }

local LOGO_MAIN_URL = "https://caen.tech/images/logos/logo-noir-jaune-grande-dimension.png"

-- --- Helpers parsing ----------------------------------------------------------
local function hm(iso)            -- "....T08:05:..." → minutes depuis minuit
    local h, m = iso:match("T(%d%d):(%d%d)")
    if not h then return nil end
    return tonumber(h) * 60 + tonumber(m)
end
local function hhmm(iso)
    local h, m = iso:match("T(%d%d):(%d%d)")
    return h and (h .. ":" .. m) or ""
end
local function extOf(url)
    return (url:match("%.([%a%d]+)$") or ""):lower()
end

-- Charge l'image `rel` (déjà sur disque) dans program.assets. Renvoie true si chargée.
local function loadAsset(id, rel)
    local okImg, img = pcall(love.graphics.newImage, rel)
    if not okImg then return false end
    img:setFilter("linear", "linear")
    if id == "logoMain" then
        program.assets.logoMain = img
    elseif id:sub(1, 4) == "spk:" then
        program.assets.speaker[id:sub(5)] = img
    elseif id:sub(1, 4) == "spo:" then
        program.assets.sponsor[id:sub(5)] = img
    end
    return true
end

-- --- Téléchargement des visuels -----------------------------------------------
-- Offline-first : le démarrage se fait potentiellement HORS LIGNE (cf. CLAUDE.md).
-- Pour chaque visuel : si une version est déjà en cache, on l'AFFICHE tout de suite,
-- PUIS on lance un téléchargement de rafraîchissement en tâche de fond. Quand (et si)
-- il aboutit, `onFetched` remplace l'image par la version fraîche ; s'il échoue
-- (hors ligne), la version en cache reste affichée.
local function requestAsset(job)
    if love.filesystem.getInfo(job.rel) then loadAsset(job.id, job.rel) end
    fetch.request(job)
end

local function requestAssets()
    requestAsset({ id = "logoMain", url = LOGO_MAIN_URL, rel = "cache/logo-main.png" })

    for id, sp in pairs(program.data.speakers) do
        if sp.photoUrl and sp.photoUrl ~= "" then
            local e = extOf(sp.photoUrl)
            if e == "jpg" or e == "jpeg" or e == "png" then
                requestAsset({ id = "spk:" .. id, url = sp.photoUrl, rel = "cache/spk/" .. id .. "." .. e })
            end
        end
    end

    for _, s in ipairs(program.data.sponsors) do
        if s.logo and s.logo ~= "" then
            local e = extOf(s.logo)
            local conv = (e == "webp" and "webp") or (e == "svg" and "svg") or nil
            local rel  = conv and ("cache/sponsor/" .. s.id .. ".png")
                         or ("cache/sponsor/" .. s.id .. "." .. e)
            requestAsset({ id = "spo:" .. s.id, url = s.logo, rel = rel, conv = conv })
        end
    end
end

function program.onFetched(events)
    for _, e in ipairs(events) do
        if e.id == "program" then
            if e.ok then
                local raw = love.filesystem.read(e.rel)
                program.parse(raw, "réseau")
            else
                program.status = { state = "error", message = e.err or "échec du téléchargement", sessions = 0 }
            end
        elseif e.ok then
            -- Rafraîchissement abouti : remplace la version (éventuellement) chargée du cache.
            loadAsset(e.id, e.rel)
        end
    end
end

-- --- Chargement / parsing -----------------------------------------------------
function program.fetchFromUrl(url)
    program.status = { state = "loading", message = "Téléchargement…", sessions = 0 }
    fetch.request({ id = "program", url = url, rel = "cache/program.json" })
end

function program.loadFromCache()
    if love.filesystem.getInfo("cache/program.json") then
        local raw = love.filesystem.read("cache/program.json")
        return program.parse(raw, "cache")
    end
    return false
end

-- Parse le JSON brut et construit le modèle.
function program.parse(raw, source)
    local ok, d = pcall(json.decode, raw)
    if not ok or type(d) ~= "table" or not d.sessions then
        program.status = { state = "error", message = "JSON invalide", sessions = 0 }
        return false
    end
    program.data = d

    -- event / date
    local date = (d.event and d.event.date) or "2026-06-09"
    local y, mo, da = date:match("(%d%d%d%d)-(%d%d)-(%d%d)")
    program.dateLabel = da and (da .. " / " .. mo .. " / " .. y) or date
    program.venue = (d.event and d.event.venue and d.event.venue.name) or ""
    program.eventName = (d.event and d.event.name) or "Caen.Tech"

    -- sessions triées
    local sessions = {}
    for _, s in pairs(d.sessions) do sessions[#sessions + 1] = s end
    table.sort(sessions, function(a, b) return a.startTime < b.startTime end)
    program.sessions = sessions

    -- résolution speaker ids → noms
    local function speakerNames(ids)
        if not ids or #ids == 0 then return "" end
        local names = {}
        for _, id in ipairs(ids) do
            local sp = d.speakers[id]
            names[#names + 1] = sp and sp.name or id
        end
        return table.concat(names, " · ")
    end
    local function firstPhoto(ids)
        if ids and ids[1] then return ids[1] end
        return nil
    end
    program.speakerNames = speakerNames

    -- Construction des créneaux (lignes du programme), groupés par startTime.
    local rows, order, byStart = {}, {}, {}
    for _, s in ipairs(sessions) do
        if not byStart[s.startTime] then byStart[s.startTime] = {}; order[#order + 1] = s.startTime end
        table.insert(byStart[s.startTime], s)
    end

    local slots = {}   -- créneaux « talk » (avec conf/amphi) pour l'écran « à suivre »
    for _, st in ipairs(order) do
        local group = byStart[st]
        local brk, conf, amphi
        for _, s in ipairs(group) do
            if (s.kind == "break" or s.kind == "animation") and (s.roomId == "all") then
                brk = s
            elseif s.roomId == "auditorium" then
                amphi = s
            else
                conf = s
            end
        end
        if brk then
            rows[#rows + 1] = { isBreak = true, time = hhmm(brk.startTime), title = brk.title, sub = "" }
        else
            local row = {
                isTalk = true, time = hhmm(st),
                startMin = hm(st), endMin = hm((conf or amphi).endTime),
            }
            if conf then
                row.conf = { title = conf.title, sp = speakerNames(conf.speakers), theme = conf.theme,
                             photoId = firstPhoto(conf.speakers), endLabel = hhmm(conf.endTime) }
            end
            if amphi then
                row.amphi = { title = amphi.title, sp = speakerNames(amphi.speakers), theme = amphi.theme,
                              photoId = firstPhoto(amphi.speakers), endLabel = hhmm(amphi.endTime) }
            end
            rows[#rows + 1] = row
            slots[#slots + 1] = row
        end
    end
    program.rows  = rows
    program.slots = slots

    -- sponsors par tier
    program.sponsors = { gold = {}, silver = {}, bronze = {} }
    for _, s in ipairs(d.sponsors or {}) do
        local t = program.sponsors[s.tier]
        if t then t[#t + 1] = { id = s.id, name = s.name } end
    end

    program.status = {
        state = "ok",
        message = string.format("Chargé (%s) · %d sessions", source or "?", #sessions),
        sessions = #sessions,
    }
    requestAssets()
    return true
end

-- --- Accès dérivés pour l'écran « à suivre » ----------------------------------
-- Renvoie le créneau courant (now ∈ [start,end[) sinon le prochain, sinon nil.
function program.currentOrNext(nowMin)
    local current, nextSlot
    for _, sl in ipairs(program.slots or {}) do
        if sl.startMin and sl.endMin and nowMin >= sl.startMin and nowMin < sl.endMin then
            current = sl
        elseif sl.startMin and sl.startMin > nowMin and not nextSlot then
            nextSlot = sl
        end
    end
    return current, nextSlot
end

return program
