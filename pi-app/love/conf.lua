--
-- Configuration de l'application d'affichage LÖVE (signage Caen.tech).
-- En production sur le Pi : plein écran "desktop" → s'adapte automatiquement à la
-- résolution HDMI réelle (1920x1080, 1280x720, etc.), pas de bordure, vsync activée.
-- Validation locale : si CAENTECH_WINDOWED est défini, on bascule en fenêtré 1280x720
-- pour pouvoir tester sans monopoliser l'écran.
--
-- Les modules `thread` (rendu headless en tâche de fond), `image` et `graphics`
-- restent actifs (défaut LÖVE) ; on coupe seulement l'audio/physique/etc. inutiles.
--
function love.conf(t)
    t.identity = "caentech-signage"
    t.version = "11.5"

    -- Mode pré-téléchargement (CAENTECH_PREFETCH) : utilisé par pi-app/setup.sh la
    -- veille de l'événement, EN LIGNE, pour remplir le cache (programme + visuels)
    -- avant un éventuel boot HORS LIGNE le jour J. On tourne alors SANS fenêtre ni
    -- graphismes (le Pi est souvent provisionné sans écran : pas de framebuffer DRM),
    -- en pur logique + réseau (curl, cf. src/fetch_thread.lua) + filesystem. La boucle
    -- principale (event/timer/update) suffit ; love.run ne dessine pas si graphics est
    -- désactivé. Voir main.lua pour la logique de complétion + sortie.
    if os.getenv("CAENTECH_PREFETCH") then
        t.modules.window   = false
        t.modules.graphics = false
        t.modules.image    = false
        t.modules.font     = false
        t.modules.audio = false
        t.modules.sound = false
        t.modules.physics = false
        t.modules.joystick = false
        t.modules.touch = false
        t.modules.video = false
        return
    end

    local windowed = os.getenv("CAENTECH_WINDOWED")
    -- Export web (love.js, cf. pi-app/web) : fenêtre fixe 1920×1080. La page HTML met
    -- ensuite le canvas à l'échelle de la fenêtre du navigateur en conservant le ratio
    -- (object-fit). Inerte hors web (love.system absent en conf → garde pcall-équivalente).
    local isWeb = love.system and love.system.getOS and love.system.getOS() == "Web"

    t.window.title = "Caen.tech"
    t.window.vsync = 1
    t.window.resizable = false
    t.window.highdpi = true

    if isWeb then
        t.window.width = 1920
        t.window.height = 1080
        t.window.fullscreen = false
        t.window.borderless = false
    elseif windowed then
        t.window.width = 1280
        t.window.height = 720
        t.window.fullscreen = false
        t.window.borderless = false
    else
        -- Plein écran "desktop" : on adopte la résolution HDMI courante du Pi.
        t.window.width = 1920
        t.window.height = 1080
        t.window.fullscreen = true
        t.window.fullscreentype = "desktop"
        t.window.borderless = true
    end

    -- audio/sound : nécessaires à la musique de fond (MP3 en boucle, cf. src/music.lua).
    -- Modules réellement inutiles pour l'affichage : on les coupe.
    t.modules.physics = false
    t.modules.joystick = false
    t.modules.touch = false
    t.modules.video = false
end
