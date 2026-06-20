--
-- Contrôleur de la boucle d'interstices : enchaîne les 4 écrans avec une
-- transition « wipe » colorée, et dessine le chrome commun (logo, horloge, label,
-- points de progression). Reproduit la logique de la maquette DCLogic.
--
local theme = require("src.theme")
local ui    = require("src.ui")
local clock = require("src.clock")
local lg    = love.graphics

local screens = {
    require("src.screens.programme"),
    require("src.screens.next"),
    require("src.screens.infos"),
    require("src.screens.sponsors"),
}
local LABELS = { "Programme de la journée", "À suivre", "Infos pratiques", "Nos sponsors" }

local M = {}

local TRANS_DUR = 1.1
local DWELL = { 18, 12, 12, 14 }   -- programme plus long

local prog, conf
local cur = 1
local screenT = 0
local timer = 0
local trans = nil   -- { t, next, swapped, frozen }

function M.load(programModel, config)
    prog = programModel
    conf = config
    cur = 1; screenT = 0; timer = 0; trans = nil
end

function M.setScreen(i)
    cur = ((i - 1) % 4) + 1
    screenT = 0; timer = 0; trans = nil
end

-- Navigue d'un écran (delta = +1 suivant, -1 précédent), avec bouclage.
function M.cycleScreen(delta)
    M.setScreen(cur + delta)
end

-- Fige une transition à mi-parcours (pour la capture d'écran « transition »).
function M.freezeTransition(nextIdx)
    cur = ((nextIdx - 2) % 4) + 1
    trans = { t = TRANS_DUR * 0.32, next = ((nextIdx - 1) % 4) + 1, swapped = false, frozen = true }
end

local function startTrans()
    trans = { t = 0, next = (cur % 4) + 1, swapped = false }
end

-- Avance à l'écran suivant en jouant la transition « wipe » (contrairement à
-- setScreen/cycleScreen qui sautent instantanément). Sans effet si une
-- transition est déjà en cours.
function M.advanceWithTransition()
    if not trans then startTrans() end
end

function M.update(dt)
    screenT = screenT + dt
    if trans then
        if trans.frozen then return end
        trans.t = trans.t + dt
        if not trans.swapped and trans.t >= TRANS_DUR / 2 then
            cur = trans.next; trans.swapped = true; screenT = 0
        end
        if trans.t >= TRANS_DUR then trans = nil; timer = 0 end
    else
        timer = timer + dt
        if timer >= DWELL[cur] then startTrans() end
    end
end

-- --- Chrome commun ------------------------------------------------------------
local function drawChrome(W, H, time)
    -- logo + baseline (haut-gauche)
    local lx, ly = 84, 56
    if prog.assets.logoMain then
        local img = prog.assets.logoMain
        local s = 66 / img:getHeight()
        lg.setColor(1, 1, 1, 1)
        lg.draw(img, lx, ly, 0, s, s)
    else
        ui.setColor(theme.color.ink)
        lg.setFont(theme.font(54, "bold"))
        lg.print("Caen", lx, ly)
        local w = theme.font(54, "bold"):getWidth("Caen")
        ui.setColor(theme.color.gold)
        lg.print(".tech", lx + w, ly)
    end
    ui.setColor(theme.color.muted2)
    ui.spacedText(theme.font(21, "bold"), "LES RENCONTRES DE L'ÉCOSYSTÈME TECH CAENNAIS", lx, ly + 80, 3, "left")

    -- horloge + date (haut-droite)
    local hh, mm = clock.hhmm()
    local clockFont = theme.font(92, "bold")
    local rx = W - 84
    local wHH = clockFont:getWidth(hh)
    local wColon = clockFont:getWidth(":")
    local wMM = clockFont:getWidth(mm)
    local total = wHH + wColon + wMM
    local cxr = rx - total
    ui.setColor(theme.color.ink)
    lg.setFont(clockFont)
    lg.print(hh, cxr, ly - 8)
    local blink = (math.floor(time * 2) % 2 == 0) and 1 or 0.18
    ui.setColor(theme.color.gold, blink)
    lg.print(":", cxr + wHH, ly - 8)
    ui.setColor(theme.color.ink)
    lg.print(mm, cxr + wHH + wColon, ly - 8)
    local dateStr = prog.dateLabel .. (prog.venue ~= "" and (" · Le " .. prog.venue) or "")
    ui.setColor(theme.color.muted2)
    ui.spacedText(theme.font(23, "bold"), theme.upper(dateStr), 0, ly + 92, 2.4, "right", rx)

    -- bannière « vous êtes ici » (optionnelle, centrée)
    if conf.thisRoom and conf.thisRoom ~= "none" then
        local roomName = conf.thisRoom == "conference" and "Conférence" or "Amphithéâtre"
        local roomColor = conf.thisRoom == "conference" and theme.color.roomConf or theme.color.roomAmphi
        local f = theme.font(20, "bold")
        local label = "VOUS ÊTES EN SALLE " .. theme.upper(roomName)
        local pw = ui.spacedWidth(f, label, 2) + 44
        ui.pill(label, f, W / 2 - pw / 2, 178, 22, 8, roomColor, theme.color.ink, 2)
    end

    -- label d'écran (bas-gauche)
    local accent = theme.accents[cur]
    ui.setColor(accent)
    ui.rrect("fill", 84, H - 66, 14, 14, 3)
    ui.setColor(theme.color.muted3)
    ui.spacedText(theme.font(24, "bold"), theme.upper(LABELS[cur]), 110, H - 68, 4, "left")

    -- points de progression (bas-droite)
    local dx = W - 84 - (4 * 38 + 3 * 12)
    for i = 1, 4 do
        ui.setColor(i == cur and theme.color.ink or theme.color.hair)
        ui.rrect("fill", dx + (i - 1) * 50, H - 60, 38, 7, 4)
    end
end

-- --- Panneau de transition « wipe » -------------------------------------------
-- smoothstep classique (0..1).
local function smoothstep(t)
    t = math.max(0, math.min(1, t))
    return t * t * (3 - 2 * t)
end

-- Approche de la courbe cubic-bezier(.83,0,.17,1) de la maquette : un ease-in-out
-- très marqué (quintique) — démarrage et fin lents, milieu rapide.
local function easeStrong(t)
    t = math.max(0, math.min(1, t))
    if t < 0.5 then return 16 * t * t * t * t * t end
    local f = -2 * t + 2
    return 1 - (f * f * f * f * f) / 2
end

-- Bandeau plein couleur (teinte vive de l'écran à venir) qui monte depuis le bas,
-- s'immobilise en couvrant tout l'écran le temps d'annoncer le prochain écran
-- (trois carrés qui « pop », son nom en grand, un sous-titre de marque), puis sort
-- par le haut. Reproduit l'animation `wipe` + `squarePop` + `fadeUp` de la maquette.
-- Le contenu de l'écran est échangé à mi-parcours (M.update), masqué par le bandeau.
local function drawTransition(W, H)
    local p = trans.t / TRANS_DUR
    -- décalage vertical du bandeau : 101% sous l'écran → 0 (couvre) → 101% au-dessus.
    local ty
    if p < 0.18 then
        ty = (1 - easeStrong(p / 0.18)) * (H * 1.01)
    elseif p < 0.82 then
        ty = 0
    else
        ty = -easeStrong((p - 0.82) / 0.18) * (H * 1.01)
    end

    lg.push()
    lg.translate(0, ty)

    -- fond plein
    ui.setColor(theme.bright[trans.next])
    lg.rectangle("fill", 0, 0, W, H)

    local ink = theme.color.ink
    local labelFont = theme.font(96, "bold")
    local subFont   = theme.font(26, "bold")
    local sqSize, sqGap, gap = 30, 14, 40
    local labelH = labelFont:getHeight()
    local subH   = subFont:getHeight()
    local total  = sqSize + gap + labelH + gap + subH
    local y0     = (H - total) / 2

    -- 1) trois carrés qui « pop » (échelonnés .05 / .15 / .25 s, durée .5 s)
    local alphas = { 1, 0.55, 0.28 }
    local delays = { 0.05, 0.15, 0.25 }
    local rowW = 3 * sqSize + 2 * sqGap
    local rowX = (W - rowW) / 2
    for i = 1, 3 do
        local sp = smoothstep((trans.t - delays[i]) / 0.5)
        if sp > 0 then
            local cx = rowX + (i - 1) * (sqSize + sqGap) + sqSize / 2
            local cy = y0 + sqSize / 2
            lg.push()
            lg.translate(cx, cy)
            lg.rotate(math.rad(-25 * (1 - sp)))
            lg.scale(sp, sp)
            ui.setColor(ink, alphas[i])
            ui.rrect("fill", -sqSize / 2, -sqSize / 2, sqSize, sqSize, 7)
            lg.pop()
        end
    end

    -- 2) nom de l'écran à venir (léger glissement vers le haut, délai .12 s)
    local labSlide = (1 - smoothstep((trans.t - 0.12) / 0.55)) * 30
    ui.setColor(ink)
    lg.setFont(labelFont)
    lg.printf(LABELS[trans.next], (W - 1400) / 2, y0 + sqSize + gap + labSlide, 1400, "center")

    -- 3) sous-titre de marque (glissement, délai .2 s)
    local subSlide = (1 - smoothstep((trans.t - 0.2) / 0.55)) * 30
    local subStr = theme.upper("Caen.Tech · " .. prog.dateLabel)
    ui.setColor(ink, 0.6)
    ui.spacedText(subFont, subStr, 0, y0 + sqSize + gap + labelH + gap + subSlide, 26 * 0.4, "center", W)

    lg.pop()
end

-- ctx commun passé aux écrans
local function buildCtx(W, H)
    return {
        now = clock.nowMinutes(),
        t = screenT,
        time = love.timer.getTime(),
        W = W, H = H,
        dwell = DWELL[cur],
        thisRoom = conf.thisRoom,
        roomPriority = conf.roomPriority,
    }
end

function M.draw(W, H, time)
    -- contenu de l'écran courant
    local ok, err = pcall(screens[cur].draw, prog, buildCtx(W, H))
    if not ok then
        ui.setColor(theme.color.ink)
        lg.setFont(theme.font(20))
        lg.print("Erreur écran : " .. tostring(err), 84, 300)
    end
    -- chrome (logo, horloge, label, points de progression)
    drawChrome(W, H, time)
    -- bandeau de transition par-dessus tout (recouvre le chrome, annonce l'écran)
    if trans then drawTransition(W, H) end
end

return M
