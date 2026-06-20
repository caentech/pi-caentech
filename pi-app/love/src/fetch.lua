--
-- API côté thread principal pour le téléchargement asynchrone (cf. fetch_thread.lua).
-- Les fichiers sont écrits dans <saveDir>/<rel> par curl (chemin RÉEL) et relus par
-- LÖVE via le chemin relatif `rel` (le save dir est monté dans love.filesystem).
--
local fetch = {}

-- Export web (love.js, cf. pi-app/web) : le navigateur n'offre NI sous-processus
-- (curl, dwebp/rsvg/magick) NI thread fiable. Le téléchargement est donc désactivé :
-- tous les points d'entrée deviennent des no-op et l'app tourne en lecture seule sur
-- le cache EMBARQUÉ dans le .love (pré-rempli au build, cf. pi-app/web/build-web.sh).
-- L'affichage reste exactement le même, offline-first, simplement sans rafraîchissement.
local WEB = love.system.getOS() == "Web"

local reqCh, doneCh, thread, saveDir
local pending = {}   -- id → rel (pour remapper les réponses)

local function parentDir(rel)
    return rel:match("^(.*)/[^/]+$")
end

function fetch.start()
    if WEB then return end
    saveDir = love.filesystem.getSaveDirectory()
    love.filesystem.createDirectory("cache")
    reqCh  = love.thread.getChannel("fetch_request")
    doneCh = love.thread.getChannel("fetch_done")
    thread = love.thread.newThread("src/fetch_thread.lua")
    thread:start()
end

-- Demande un téléchargement. job = { id, url, rel, conv = nil|"webp"|"svg", maxH = nil|px }
-- `rel` : chemin relatif au save dir (ex. "cache/logo/x.png").
-- `maxH` : hauteur max en px — l'image PNG produite est réduite si plus haute (Pi).
function fetch.request(job)
    if WEB then return end
    local dir = parentDir(job.rel)
    if dir then love.filesystem.createDirectory(dir) end
    pending[job.id] = job.rel
    reqCh:push({ id = job.id, url = job.url, out = saveDir .. "/" .. job.rel, conv = job.conv, maxH = job.maxH })
end

-- Récupère les téléchargements terminés. Renvoie une liste { id, rel, ok, err }.
function fetch.poll()
    if WEB then return {} end
    local out = {}
    local r = doneCh:pop()
    while r do
        out[#out + 1] = { id = r.id, rel = pending[r.id], ok = r.ok, err = r.err }
        pending[r.id] = nil
        r = doneCh:pop()
    end
    return out
end

-- Nombre de téléchargements encore en cours (demandés, pas encore terminés).
-- Sert au mode pré-téléchargement headless pour savoir quand le cache est complet.
function fetch.pendingCount()
    if WEB then return 0 end
    local n = 0
    for _ in pairs(pending) do n = n + 1 end
    return n
end

function fetch.stop()
    if WEB then return end
    if reqCh then reqCh:push({ quit = true }) end
end

return fetch
