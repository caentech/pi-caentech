--
-- Écran 1 — Programme de la journée : grille Heure / Conférence / Amphithéâtre,
-- défilement vertical automatique, mise en évidence du créneau « EN COURS ».
--
local theme = require("src.theme")
local ui    = require("src.ui")
local lg    = love.graphics

local M = {}

local PAD   = 84
local TOP   = 236
local BOT   = 116
local TIMEW = 132
local GAP   = 22

local TALK_H  = 100
local BREAK_H = 60
local ROW_GAP = 12

-- Dessine une carte talk (conférence ou amphi). `data` peut être nil.
local function talkCard(data, x, y, w, h, current, isAmphi)
    local track = data and theme.trackColor(data.theme) or theme.hair
    local fill  = current and theme.hex("#4f9f3e", 0.12) or theme.color.white
    ui.card(x, y, w, h, 14, {
        fill = fill, bar = "left", barColor = track, barSize = 6,
        border = theme.hex("#000000", 0.06), echo = track, echoDx = 7, echoDy = 8,
    })
    local ix, iw = x + 22, w - 40
    if not data then
        ui.setColor(theme.color.faint)
        lg.setFont(theme.font(25, "bold"))
        lg.print("—", ix, y + h / 2 - 18)
        return
    end
    local ty = y + 16
    if current and not isAmphi then
        ui.pill("EN COURS", theme.font(15, "bold"), ix, ty, 9, 4,
            theme.color.green, theme.color.white, 1.2)
        ty = ty + 2
        ui.textClamped(theme.font(24, "bold"), data.title, ix + 124, y + 16, iw - 124,
            theme.color.ink, 2, 28)
    else
        ui.textClamped(theme.font(24, "bold"), data.title, ix, ty, iw,
            isAmphi and theme.color.ink or theme.color.ink, 2, 28)
    end
    if data.sp and data.sp ~= "" then
        ui.setColor(theme.color.muted)
        lg.setFont(theme.font(18))
        lg.print(data.sp, ix, y + h - 28)
    end
end

function M.draw(prog, ctx)
    local now = ctx.now
    local W = ctx.W
    local x0 = PAD
    local fullW = W - PAD * 2
    local colW = (fullW - TIMEW - GAP * 2) / 2
    local confX  = x0 + TIMEW + GAP
    local amphiX = confX + colW + GAP

    -- entête de colonnes
    local hy = TOP - 0
    ui.setColor(theme.color.muted2)
    ui.spacedText(theme.font(20, "bold"), "HEURE", x0, hy, 3, "left")
    local confLbl  = (ctx.thisRoom == "conference") and "CONFÉRENCE  ◂ ICI" or "CONFÉRENCE"
    local amphiLbl = (ctx.thisRoom == "auditorium") and "AMPHITHÉÂTRE  ◂ ICI" or "AMPHITHÉÂTRE"
    ui.setColor(theme.color.gold)
    ui.spacedText(theme.font(20, "bold"), confLbl, confX, hy, 3, "left")
    ui.setColor(theme.hex("#3fa6b3"))
    ui.spacedText(theme.font(20, "bold"), amphiLbl, amphiX, hy, 3, "left")

    -- zone défilante
    local areaY = TOP + 44
    local areaH = ctx.H - BOT - areaY
    ui.pushClip(x0, areaY, fullW, areaH)

    -- hauteur totale du contenu
    local total = 0
    for _, r in ipairs(prog.rows) do total = total + (r.isBreak and BREAK_H or TALK_H) + ROW_GAP end
    local overflow = math.max(0, total - areaH)

    -- défilement : maintien aux extrémités puis glissement (cf. keyframe scrollY)
    local cycle = ctx.dwell
    local p = (ctx.t % cycle) / cycle
    local scroll = 0
    if overflow > 0 then
        local a, b = 0.12, 0.88
        local f = (p <= a) and 0 or (p >= b) and 1 or ((p - a) / (b - a))
        f = f * f * (3 - 2 * f) -- smoothstep
        scroll = -overflow * f
    end

    local y = areaY + scroll
    for _, r in ipairs(prog.rows) do
        if r.isBreak then
            ui.setColor(theme.color.faint)
            lg.setFont(theme.font(26, "bold"))
            lg.print(r.time, x0, y + BREAK_H / 2 - 18)
            lg.setColor(theme.color.hair[1], theme.color.hair[2], theme.color.hair[3], 1)
            lg.setLineWidth(1)
            lg.line(confX, y + 14, x0 + fullW, y + 14)
            ui.setColor(theme.color.muted2)
            lg.setFont(theme.font(22, "bold"))
            lg.print(r.title, confX, y + BREAK_H / 2 - 16)
            y = y + BREAK_H + ROW_GAP
        else
            local current = r.startMin and now >= r.startMin and now < r.endMin
            -- heure
            ui.setColor(current and theme.color.green or theme.color.ink2)
            lg.setFont(theme.font(30, "bold"))
            lg.print(r.time, x0, y + TALK_H / 2 - 20)
            -- halo si en cours
            if current then
                ui.setColor(theme.hex("#4f9f3e", 0.5))
                lg.setLineWidth(2)
                ui.rrect("line", confX - 3, y - 3, colW + 6, TALK_H + 6, 16)
                ui.rrect("line", amphiX - 3, y - 3, colW + 6, TALK_H + 6, 16)
            end
            talkCard(r.conf, confX, y, colW, TALK_H, current, false)
            talkCard(r.amphi, amphiX, y, colW, TALK_H, current, true)
            y = y + TALK_H + ROW_GAP
        end
    end
    ui.popClip()

    -- masques dégradés haut / bas
    local cream = theme.color.cream
    local mh = 46
    -- haut
    for i = 0, mh do
        local a = 1 - i / mh
        lg.setColor(cream[1], cream[2], cream[3], a)
        lg.rectangle("fill", x0, areaY + i, fullW, 1)
    end
    for i = 0, mh do
        local a = i / mh
        lg.setColor(cream[1], cream[2], cream[3], a)
        lg.rectangle("fill", x0, areaY + areaH - mh + i, fullW, 1)
    end
end

return M
