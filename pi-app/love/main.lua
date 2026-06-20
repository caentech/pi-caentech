--
-- Application d'affichage LÖVE — signage Caen.tech (le vrai produit).
--
-- Deux états :
--   * SETUP  : écran de configuration (URL du programme, mise à jour, heure, mode).
--   * LOOP   : boucle d'interstices (Programme, À suivre, Infos pratiques, Sponsors)
--              générés depuis le programme JSON, rendus nativement.
--
-- Tout est dessiné dans un canvas de référence 1920×1080 puis mis à l'échelle vers
-- la fenêtre (le scissor/stencil travaillent ainsi en coordonnées natives).
--
-- Réseau (programme + visuels) en tâche de fond via curl (src/fetch*.lua).
--
-- Clavier en boucle : 1-4 saute à un écran · ←/→ écran précédent/suivant ·
-- Espace passe à l'écran suivant avec la transition · Entrée change la salle de
-- l'écran (aucune→conférence→amphi) · ↑/↓ (Maj=±15) règlent l'heure ·
-- S/Échap reviennent au setup · Q quitte.
--
local background = require("src.background")
local config     = require("src.config")
local clock      = require("src.clock")
local fetch      = require("src.fetch")
local program    = require("src.program")
local setup      = require("src.setup_screen")
local interstice = require("src.interstice")

local STAGE_W, STAGE_H = 1920, 1080

local state = "setup"        -- "setup" | "loop"
local cfg
local stage                  -- canvas 1920×1080
local layout = { s = 1, ox = 0, oy = 0 }

-- mode capture (vérif) : CAENTECH_SHOT="programme@10:20" | "next@..." | "infos" |
-- "sponsors" | "transition" | "setup"
local shot = { active = false, delay = 8, t = 0 }

-- --- Bascule d'états ----------------------------------------------------------
local function enterLoop()
    interstice.load(program, cfg)
    state = "loop"
    love.mouse.setVisible(false)
end

local function enterSetup()
    setup.load(cfg, program)
    state = "setup"
    love.mouse.setVisible(true)
end

setup.onStart = enterLoop

-- Salle de cet écran, cyclée à chaud (Entrée) : none → conférence → amphi → none.
local ROOM_CYCLE = { none = "conference", conference = "auditorium", auditorium = "none" }
local function cycleRoom()
    cfg.thisRoom = ROOM_CYCLE[cfg.thisRoom] or "conference"
end

-- --- Cycle de vie -------------------------------------------------------------
function love.load()
    io.stdout:setvbuf("line")
    love.math.setRandomSeed(os.time())

    cfg = config.load()
    background.load()
    fetch.start()

    -- URL effective : override par env (dev/capture), sinon réglage sauvegardé.
    local url = os.getenv("CAENTECH_PROGRAM_URL") or cfg.programUrl
    cfg.programUrl = url
    program.loadFromCache()
    program.fetchFromUrl(url)

    stage = love.graphics.newCanvas(STAGE_W, STAGE_H)
    stage:setFilter("linear", "linear")

    enterSetup()

    -- Mode capture scriptable.
    local spec = os.getenv("CAENTECH_SHOT")
    if spec then
        shot.active = true
        shot.delay = tonumber(os.getenv("CAENTECH_SHOT_DELAY") or "8") or 8
        shot.out = os.getenv("CAENTECH_SHOT_OUT") or (love.filesystem.getSaveDirectory() .. "/shot.png")
        local screen, time = spec:match("^([%a]+)@(.+)$")
        screen = screen or spec
        if time then clock.setManual(time) end
        local map = { programme = 1, next = 2, infos = 3, sponsors = 4 }
        if screen == "setup" then
            enterSetup()
        elseif screen == "transition" then
            enterLoop(); interstice.freezeTransition(2)
        else
            enterLoop(); interstice.setScreen(map[screen] or 1)
        end
    end
end

local function computeLayout()
    local W, H = love.graphics.getDimensions()
    local s = math.min(W / STAGE_W, H / STAGE_H)
    layout.s = s
    layout.ox = (W - STAGE_W * s) / 2
    layout.oy = (H - STAGE_H * s) / 2
end

function love.update(dt)
    program.onFetched(fetch.poll())
    if state == "setup" then setup.update(dt) else interstice.update(dt) end

    if shot.active then
        shot.t = shot.t + dt
        if state == "loop" then interstice.update(0) end
    end
end

function love.draw()
    local t = love.timer.getTime()
    computeLayout()

    love.graphics.setCanvas({ stage, stencil = true })
    love.graphics.clear(0.933, 0.913, 0.863, 1)
    background.draw(STAGE_W, STAGE_H, t)
    if state == "setup" then setup.draw(STAGE_W, STAGE_H, t)
    else interstice.draw(STAGE_W, STAGE_H, t) end
    love.graphics.setCanvas()

    -- capture (avant blit) : canvas propre 1920×1080
    if shot.active and shot.t >= shot.delay and not shot.done then
        shot.done = true
        local data = stage:newImageData()
        local fd = data:encode("png")
        local f = io.open(shot.out, "wb")
        if f then f:write(fd:getString()); f:close(); print("[shot] écrit : " .. shot.out) end
        love.event.quit()
    end

    -- blit mis à l'échelle, centré (letterbox)
    local W, H = love.graphics.getDimensions()
    love.graphics.clear(0.06, 0.06, 0.07, 1)
    love.graphics.setColor(1, 1, 1, 1)
    love.graphics.draw(stage, layout.ox, layout.oy, 0, layout.s, layout.s)
end

-- --- Entrées ------------------------------------------------------------------
local function toStage(x, y)
    return (x - layout.ox) / layout.s, (y - layout.oy) / layout.s
end

function love.keypressed(key)
    if key == "q" then love.event.quit(); return end
    if state == "setup" then
        setup.keypressed(key)
        return
    end
    -- boucle
    local shift = love.keyboard.isDown("lshift", "rshift")
    if key == "escape" or key == "s" then enterSetup()
    elseif key == "1" then interstice.setScreen(1)
    elseif key == "2" then interstice.setScreen(2)
    elseif key == "3" then interstice.setScreen(3)
    elseif key == "4" then interstice.setScreen(4)
    elseif key == "space" then interstice.advanceWithTransition()
    elseif key == "up" then clock.adjust(shift and 15 or 1)
    elseif key == "down" then clock.adjust(shift and -15 or -1)
    elseif key == "left" then interstice.cycleScreen(-1)
    elseif key == "right" then interstice.cycleScreen(1)
    elseif key == "return" or key == "kpenter" then cycleRoom()
    end
end

function love.textinput(t)
    if state == "setup" then setup.textinput(t) end
end

function love.mousepressed(x, y, button)
    if state == "setup" then
        local sx, sy = toStage(x, y)
        setup.mousepressed(sx, sy, button)
    end
end

function love.quit()
    fetch.stop()
end
