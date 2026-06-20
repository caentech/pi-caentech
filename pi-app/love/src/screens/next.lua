--
-- Écran 2 — À suivre / En cours : badge pulsé, sous-titre, et 1 à 2 cartes
-- (pastille salle, photo ronde du speaker, titre, intervenant, track).
-- Respecte la priorité salle ; fin de journée → écran « Merci ».
--
local theme = require("src.theme")
local ui    = require("src.ui")
local lg    = love.graphics

local M = {}
local PAD = 84
local TOP = 244
local BOT = 116

local function pad(n) return n < 10 and ("0" .. n) or tostring(n) end

-- Construit les cartes à afficher selon la priorité salle.
local function buildCards(slot, priority)
    local cards = {}
    local function add(d, roomName, roomColor)
        if d then cards[#cards + 1] = { d = d, room = roomName, roomColor = roomColor } end
    end
    if priority == "conference" then
        add(slot.conf, "Conférence", theme.color.roomConf)
    elseif priority == "auditorium" then
        add(slot.amphi, "Amphithéâtre", theme.color.roomAmphi)
    else
        add(slot.conf, "Conférence", theme.color.roomConf)
        add(slot.amphi, "Amphithéâtre", theme.color.roomAmphi)
    end
    -- repli : si la salle prioritaire n'a pas de session, montrer l'autre
    if #cards == 0 then
        add(slot.conf, "Conférence", theme.color.roomConf)
        add(slot.amphi, "Amphithéâtre", theme.color.roomAmphi)
    end
    return cards
end

local function drawCard(prog, c, x, y, w, h, t)
    local track = theme.trackColor(c.d.theme)
    ui.card(x, y, w, h, 28, {
        fill = theme.color.white, bar = "top", barColor = track, barSize = 7,
        border = theme.hex("#000000", 0.06), echo = track, echoDx = 14, echoDy = 16,
    })
    local cx = x + w / 2
    local cy = y + 34
    -- pastille salle
    local lblFont = theme.font(22, "bold")
    local pw = ui.spacedWidth(lblFont, theme.upper(c.room), 1.5) + 44
    ui.pill(theme.upper(c.room), lblFont, cx - pw / 2, cy, 22, 9, c.roomColor, theme.color.ink, 1.5)
    cy = cy + lblFont:getHeight() + 18 + 24

    -- photo ronde
    local img = c.d.photoId and prog.assets.speaker[c.d.photoId] or nil
    if c.d.photoId then
        ui.circlePhoto(img, cx, cy + 70, 70, track, 5)
        cy = cy + 170
    end

    -- titre (3 lignes max), centré
    lg.setFont(theme.font(29, "bold"))
    local _, lines = theme.font(29, "bold"):getWrap(c.d.title, w - 80)
    local nT = math.min(#lines, 3)
    ui.setColor(theme.color.ink)
    for i = 1, nT do
        lg.printf(lines[i], x + 40, cy + (i - 1) * 34, w - 80, "center")
    end
    cy = cy + nT * 34 + 12

    -- intervenant
    if c.d.sp and c.d.sp ~= "" then
        ui.setColor(theme.color.muted3)
        lg.setFont(theme.font(22, "bold"))
        lg.printf(c.d.sp, x + 40, cy, w - 80, "center")
    end

    -- track (bas)
    if c.d.theme and c.d.theme ~= "" then
        ui.setColor(track)
        ui.spacedText(theme.font(20, "bold"), theme.upper(c.d.theme),
            x + 40, y + h - 40, 1.5, "center", w - 80)
    end
end

function M.draw(prog, ctx)
    local now = ctx.now
    local W, H = ctx.W, ctx.H
    local cx = W / 2
    local current, nextSlot = prog.currentOrNext(now)
    local slot = current or nextSlot

    -- badge + sous-titre
    local badge, badgeColor, sub
    if not slot then
        badge, badgeColor, sub = "MERCI !", theme.color.gold, "À la prochaine édition de Caen.Tech"
    elseif current then
        badge, badgeColor = "EN COURS", theme.color.green
        sub = "En salle jusqu'à " .. (slot.conf and slot.conf.endLabel or slot.amphi.endLabel)
    else
        badge, badgeColor = "À SUIVRE", theme.color.gold
        local dm = slot.startMin - now
        sub = (dm >= 60 and ("Dans " .. math.floor(dm / 60) .. " h " .. pad(dm % 60))
                or ("Dans " .. dm .. " min")) .. " · début " .. slot.time
    end

    local y = TOP
    -- pastille pulsée
    local pulse = 0.5 + 0.5 * math.sin(ctx.time * 3)
    ui.setColor(badgeColor, 0.25)
    lg.circle("fill", cx - 230, y + 18, 13 + 10 * pulse)
    ui.setColor(badgeColor)
    lg.circle("fill", cx - 230, y + 18, 13)
    ui.setColor(badgeColor)
    ui.spacedText(theme.font(36, "bold"), badge, cx - 200, y, 4, "left")
    y = y + 56

    ui.setColor(theme.color.muted3)
    lg.setFont(theme.font(30, "bold"))
    lg.printf(sub, 0, y, W, "center")
    y = y + 60

    if not slot then
        ui.setColor(theme.color.gold)
        lg.setFont(theme.font(110, "bold"))
        lg.printf("Apéritif", 0, H / 2 - 80, W, "center")
        ui.setColor(theme.color.muted3)
        lg.setFont(theme.font(34))
        lg.printf("Prolongez les échanges dans une ambiance conviviale.\nMerci d'avoir fait vivre cette première édition.",
            cx - 550, H / 2 + 70, 1100, "center")
        return
    end

    -- cartes
    local cards = buildCards(slot, ctx.roomPriority)
    local areaBottom = H - BOT
    local cardH = areaBottom - y - 10
    cardH = math.min(cardH, 540)
    local cardY = y
    local n = #cards
    local gap = 40
    local maxW = 760
    local cardW = math.min(maxW, (1752 - gap * (n - 1)) / n)
    local totalW = cardW * n + gap * (n - 1)
    local sx = cx - totalW / 2
    for i, c in ipairs(cards) do
        drawCard(prog, c, sx + (i - 1) * (cardW + gap), cardY, cardW, cardH, ctx.time)
    end
end

return M
