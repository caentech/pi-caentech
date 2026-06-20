--
-- Réglages persistés (save dir LÖVE) : URL du programme, mode d'affichage, heure.
--
local json = require("src.json")
local config = {}

local FILE = "settings.json"

local defaults = {
    programUrl   = "https://caen.tech/program/model.json",
    roomPriority = "all",      -- "all" | "conference" | "auditorium" (mise en avant)
    thisRoom     = "none",     -- "none" | "conference" | "auditorium" (salle de cet écran)
    timeMode     = "real",     -- "real" | "manual"
    manualTime   = "10:20",    -- utilisé si timeMode == "manual"
    musicEnabled = true,       -- musique de fond (MP3 dropbox) : activée par défaut
}

local function copyDefaults()
    local t = {}
    for k, v in pairs(defaults) do t[k] = v end
    return t
end

function config.load()
    local data = copyDefaults()
    if love.filesystem.getInfo(FILE) then
        local raw = love.filesystem.read(FILE)
        local ok, parsed = pcall(json.decode, raw)
        if ok and type(parsed) == "table" then
            for k, v in pairs(parsed) do data[k] = v end
        end
    end
    return data
end

function config.save(data)
    local ok, encoded = pcall(json.encode, data)
    if ok then love.filesystem.write(FILE, encoded) end
    return ok
end

return config
