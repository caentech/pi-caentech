--
-- Écran 4 — Sponsors : sections Gold / Silver / Bronze. Logo PNG (converti depuis
-- webp/svg si besoin) ou nom du sponsor en repli.
--
local theme = require("src.theme")
local ui    = require("src.ui")
local lg    = love.graphics

local M = {}
local PAD = 84
local TOP = 240
local BOT = 116
local SEC_GAP = 24

local function fadeLine(x, y, w, color)
    local steps = 60
    for i = 0, steps do
        local a = 1 - i / steps
        lg.setColor(color[1], color[2], color[3], a)
        lg.rectangle("fill", x + (w * i / steps), y, w / steps + 1, 2)
    end
end

-- Contenu d'une carte sponsor : logo centré (fit) ou nom en repli.
local function cardContent(prog, item, x, y, w, h)
    local img = prog.assets.sponsor[item.id]
    local padX, padY = 40, 26
    if img then
        local iw, ih = img:getDimensions()
        local s = math.min((w - padX * 2) / iw, (h - padY * 2) / ih)
        ui.setColor(theme.color.white) -- reset → blanc plein
        lg.setColor(1, 1, 1, 1)
        lg.draw(img, x + w / 2, y + h / 2, 0, s, s, iw / 2, ih / 2)
    else
        ui.setColor(theme.color.ink)
        lg.setFont(theme.font(math.min(28, h * 0.26), "bold"))
        lg.printf(item.name, x + 16, y + h / 2 - 18, w - 32, "center")
    end
end

local function tierHeader(label, color, x, y, w)
    ui.setColor(theme.hex(color))
    local lblW = ui.spacedText(theme.font(23, "bold"), theme.upper(label), x, y, 4, "left")
    fadeLine(x + lblW + 18, y + 14, w - lblW - 18, theme.hex(color))
end

local function card(prog, item, x, y, w, h, t, delay)
    local p = math.max(0, math.min(1, (t - delay) / 0.5))
    local sc = 0.88 + 0.12 * (p * p * (3 - 2 * p))
    lg.push()
    lg.translate(x + w / 2, y + h / 2); lg.scale(sc, sc); lg.translate(-(x + w / 2), -(y + h / 2))
    ui.card(x, y, w, h, 18, {
        fill = theme.color.white, border = theme.hex("#e6e1d3"),
        echo = theme.hex("#d6d1c2"), echoDx = 8, echoDy = 10,
    })
    cardContent(prog, item, x, y, w, h)
    lg.pop()
end

function M.draw(prog, ctx)
    local W, H = ctx.W, ctx.H
    local x0, fullW = PAD, W - PAD * 2
    local sp = prog.sponsors or { gold = {}, silver = {}, bronze = {} }
    local y = TOP

    -- Gold
    local goldH = 168
    tierHeader("Gold", "#c79a00", x0, y, fullW)
    y = y + 40
    do
        local n = math.max(1, #sp.gold)
        local gap = 28
        local cw = (fullW - gap * (n - 1)) / n
        for i, s in ipairs(sp.gold) do
            card(prog, s, x0 + (i - 1) * (cw + gap), y, cw, goldH, ctx.t, (i - 1) * 0.05)
        end
    end
    y = y + goldH + SEC_GAP

    -- Silver
    local silverH = 126
    tierHeader("Silver", "#8a847a", x0, y, fullW)
    y = y + 40
    do
        local n = math.max(1, #sp.silver)
        local gap = 28
        local cw = (fullW - gap * (n - 1)) / n
        for i, s in ipairs(sp.silver) do
            card(prog, s, x0 + (i - 1) * (cw + gap), y, cw, silverH, ctx.t, (i - 1) * 0.05)
        end
    end
    y = y + silverH + SEC_GAP

    -- Bronze (grille 4 colonnes)
    tierHeader("Bronze", "#b07f4f", x0, y, fullW)
    y = y + 40
    do
        local cols = 4
        local gap = 22
        local cw = (fullW - gap * (cols - 1)) / cols
        local areaH = H - BOT - y
        local rows = math.max(1, math.ceil(#sp.bronze / cols))
        local ch = (areaH - gap * (rows - 1)) / rows
        for i, s in ipairs(sp.bronze) do
            local col = (i - 1) % cols
            local row = math.floor((i - 1) / cols)
            card(prog, s, x0 + col * (cw + gap), y + row * (ch + gap), cw, ch, ctx.t, (i - 1) * 0.03)
        end
    end
end

return M
