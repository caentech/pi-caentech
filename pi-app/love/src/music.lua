--
-- Musique de fond (signage Caen.tech) : lit en BOUCLE un fichier MP3 déposé sur le
-- Pi via la « dropbox » de pi-manager (scp vers ~/.pi-manager/files/, cf. backend
-- DeviceService.pushFile + Config.remoteFilesDir).
--
-- Règles (cf. directive) :
--   * le MP3 vit HORS du save dir LÖVE → on le lit via io.open (Lua brut) puis on en
--     fait une source en streaming (pas tout le fichier en RAM) ;
--   * un réglage persisté (settings.json : musicEnabled) active/désactive la musique —
--     un Pi peut donc être configuré SANS musique même si le MP3 est présent ;
--   * par défaut le réglage est activé : si un MP3 est là au démarrage, ça joue.
--
-- Tout est défensif (pcall) : ni l'absence de MP3, ni un périphérique audio
-- indisponible, ni un module audio coupé (mode prefetch) ne doivent faire planter
-- l'affichage.
--
local music = {}

local source              -- source LÖVE en streaming (ou nil si aucun MP3 chargé)
local available = false   -- un MP3 a été trouvé et chargé
local enabled   = true    -- réglage courant (active/désactive la lecture)

-- Cherche le fichier MP3 à lire : override explicite (dev/test) sinon premier *.mp3
-- de la dropbox (~/.pi-manager/files), par ordre alphabétique. Renvoie un chemin
-- ABSOLU ou nil.
local function findFile()
    local override = os.getenv("CAENTECH_MUSIC_FILE")
    if override and override ~= "" then return override end

    local home = os.getenv("HOME")
    if not home or home == "" then return nil end
    local dir = home .. "/.pi-manager/files"
    -- ls trié, premier mp3 (insensible à la casse). io.popen : on lit hors save dir.
    local h = io.popen(string.format(
        "ls -1 %q/*.mp3 %q/*.MP3 2>/dev/null | head -n1", dir, dir))
    if not h then return nil end
    local path = h:read("*l")
    h:close()
    if path and path ~= "" then return path end
    return nil
end

-- Lit `path` (hors save dir) et en fait une source LÖVE en streaming. nil si échec.
local function loadSource(path)
    local f = io.open(path, "rb")
    if not f then return nil end
    local bytes = f:read("*a"); f:close()
    if not bytes or #bytes == 0 then return nil end
    local okData, data = pcall(love.filesystem.newFileData, bytes, "music.mp3")
    if not okData or not data then return nil end
    local okSrc, src = pcall(love.audio.newSource, data, "stream")
    if not okSrc or not src then return nil end
    return src
end

-- Initialise la musique. `enabledSetting` : réglage persisté (bool, défaut activé).
-- À appeler une fois au démarrage (les MP3 déposés ensuite via la dropbox ne sont
-- pris en compte qu'au prochain (re)démarrage du service — règle « au démarrage »).
function music.load(enabledSetting)
    enabled = enabledSetting ~= false
    if not love.audio then return end   -- module audio absent (ex. mode prefetch)

    local path = findFile()
    if not path then
        print("[music] aucun MP3 dans la dropbox (~/.pi-manager/files) — pas de musique.")
        return
    end
    local src = loadSource(path)
    if not src then
        print("[music] échec du chargement de " .. path .. " — pas de musique.")
        return
    end
    src:setLooping(true)
    source = src
    available = true
    if enabled then
        source:play()
        print("[music] lecture en boucle : " .. path)
    else
        print("[music] MP3 présent mais musique désactivée : " .. path)
    end
end

-- Active/désactive la lecture à chaud (réglage). Sans effet si aucun MP3 chargé.
function music.setEnabled(on)
    enabled = on and true or false
    if not source then return end
    if enabled then
        if not source:isPlaying() then source:play() end
    else
        source:stop()
    end
end

-- Un MP3 a-t-il été trouvé et chargé ? (pour griser le réglage si absent)
function music.isAvailable() return available end

return music
