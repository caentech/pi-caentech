--
-- API côté thread principal pour le téléchargement asynchrone (cf. fetch_thread.lua).
-- Les fichiers sont écrits dans <saveDir>/<rel> par curl (chemin RÉEL) et relus par
-- LÖVE via le chemin relatif `rel` (le save dir est monté dans love.filesystem).
--
local fetch = {}

local reqCh, doneCh, thread, saveDir
local pending = {}   -- id → rel (pour remapper les réponses)

local function parentDir(rel)
    return rel:match("^(.*)/[^/]+$")
end

function fetch.start()
    saveDir = love.filesystem.getSaveDirectory()
    love.filesystem.createDirectory("cache")
    reqCh  = love.thread.getChannel("fetch_request")
    doneCh = love.thread.getChannel("fetch_done")
    thread = love.thread.newThread("src/fetch_thread.lua")
    thread:start()
end

-- Demande un téléchargement. job = { id, url, rel, conv = nil|"webp"|"svg" }
-- `rel` : chemin relatif au save dir (ex. "cache/logo/x.png").
function fetch.request(job)
    local dir = parentDir(job.rel)
    if dir then love.filesystem.createDirectory(dir) end
    pending[job.id] = job.rel
    reqCh:push({ id = job.id, url = job.url, out = saveDir .. "/" .. job.rel, conv = job.conv })
end

-- Récupère les téléchargements terminés. Renvoie une liste { id, rel, ok, err }.
function fetch.poll()
    local out = {}
    local r = doneCh:pop()
    while r do
        out[#out + 1] = { id = r.id, rel = pending[r.id], ok = r.ok, err = r.err }
        pending[r.id] = nil
        r = doneCh:pop()
    end
    return out
end

function fetch.stop()
    if reqCh then reqCh:push({ quit = true }) end
end

return fetch
