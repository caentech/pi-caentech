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

    local windowed = os.getenv("CAENTECH_WINDOWED")

    t.window.title = "Caen.tech"
    t.window.vsync = 1
    t.window.resizable = false
    t.window.highdpi = true

    if windowed then
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

    -- Modules inutiles pour de l'affichage de formes : on les coupe.
    t.modules.audio = false
    t.modules.sound = false
    t.modules.physics = false
    t.modules.joystick = false
    t.modules.touch = false
    t.modules.video = false
end
