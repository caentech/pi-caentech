--
-- Thread de fond : téléchargement (curl) du programme JSON et des visuels
-- (photos speakers, logos sponsors), + conversion des logos webp/svg → png.
-- Ne bloque JAMAIS la boucle d'affichage (curl + conversion peuvent prendre du temps).
--
-- Protocole (canaux LÖVE) :
--   « fetch_request » : { id, url, out, conv = nil|"webp"|"svg" }  (ou { quit = true })
--   « fetch_done »    : { id, out, ok = bool, err = string|nil }
--
require("love.filesystem")

local reqCh  = love.thread.getChannel("fetch_request")
local doneCh = love.thread.getChannel("fetch_done")

-- Un binaire est-il dans le PATH ?
local function has(bin)
    local h = io.popen("command -v " .. bin .. " 2>/dev/null")
    if not h then return false end
    local out = h:read("*a"); h:close()
    return out ~= nil and out:gsub("%s", "") ~= ""
end

local hasDwebp = has("dwebp")
local hasCwebp = has("cwebp")          -- repli (dwebp est l'outil dédié)
local hasRsvg  = has("rsvg-convert")
local hasMagick = has("magick") or has("convert")

-- Télécharge `url` vers `dst`. Renvoie true/false.
local function download(url, dst)
    local cmd = string.format("curl -fsSL --max-time 25 -o %q %q", dst, url)
    os.execute(cmd)
    local f = io.open(dst, "rb")
    if not f then return false end
    local sz = f:seek("end"); f:close()
    return sz and sz > 0
end

-- Réduit le PNG `path` (en place) pour que sa hauteur ne dépasse pas maxH px.
-- Préserve le ratio (`>` = ne réduit que si plus grand). Nécessite ImageMagick ;
-- sans outil, ne touche à rien (le garde-fou de chargement fera le repli texte).
local function resizePng(path, maxH)
    if not maxH or not hasMagick then return end
    os.execute(string.format(
        "magick %q -resize x%d\\> %q >/dev/null 2>&1 || convert %q -resize x%d\\> %q >/dev/null 2>&1",
        path, maxH, path, path, maxH, path))
end

-- Convertit src (webp|svg) → out (png). Renvoie true/false.
local function convert(kind, src, out)
    if kind == "webp" then
        if hasDwebp then os.execute(string.format("dwebp %q -o %q >/dev/null 2>&1", src, out))
        elseif hasMagick then os.execute(string.format("magick %q %q >/dev/null 2>&1 || convert %q %q >/dev/null 2>&1", src, out, src, out)) end
    elseif kind == "svg" then
        -- Hauteur seule → largeur proportionnelle : le ratio est préservé (le rendu
        -- fait ensuite un fit dans la carte). NB : fixer -w ET -h étirerait le logo.
        if hasRsvg then os.execute(string.format("rsvg-convert -h 300 %q -o %q >/dev/null 2>&1", src, out))
        elseif hasMagick then os.execute(string.format("magick -background none -density 300 %q %q >/dev/null 2>&1 || convert -background none -density 300 %q %q >/dev/null 2>&1", src, out, src, out)) end
    end
    local f = io.open(out, "rb")
    if not f then return false end
    local sz = f:seek("end"); f:close()
    return sz and sz > 0
end

while true do
    local job = reqCh:demand()
    if job.quit then return end

    -- Écriture atomique : on produit d'abord un `.part`, renommé sur job.out seulement
    -- en cas de succès. Un rafraîchissement interrompu (réseau coupé en cours) ne
    -- corrompt donc jamais le fichier de cache dont dépend le démarrage hors ligne.
    local ok, err = false, nil
    local part = job.out .. ".part"
    if not job.conv then
        if download(job.url, part) then
            resizePng(part, job.maxH)
            ok = os.rename(part, job.out)
            if not ok then os.remove(part); err = "écriture du cache échouée" end
        else
            os.remove(part); err = "téléchargement échoué"
        end
    else
        local src = job.out .. "." .. job.conv
        if download(job.url, src) then
            if convert(job.conv, src, part) then
                resizePng(part, job.maxH)
                ok = os.rename(part, job.out)
                if not ok then os.remove(part); err = "écriture du cache échouée" end
            else
                os.remove(part); err = "conversion " .. job.conv .. " indisponible"
            end
            os.remove(src)
        else
            err = "téléchargement échoué"
        end
    end

    doneCh:push({ id = job.id, out = job.out, ok = ok, err = err })
end
