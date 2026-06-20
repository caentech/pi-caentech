--
-- Écran 3 — Infos pratiques : 6 cartes (lieu, en salle, jeu fil rouge, retours,
-- communauté, Wi-Fi). Contenu repris de la maquette, lieu enrichi par le JSON.
--
local theme = require("src.theme")
local ui    = require("src.ui")
local lg    = love.graphics

local M = {}
local PAD = 84
local TOP = 244
local BOT = 116
local GAP = 20

function M.draw(prog, ctx)
    local W, H = ctx.W, ctx.H
    local venue = prog.venue ~= "" and ("Le " .. prog.venue) or "Le MoHo"
    local city  = (prog.data and prog.data.event and prog.data.event.venue and prog.data.event.venue.city) or "Caen"

    local cards = {
        { c = "#3fa6b3", label = "LE LIEU",            title = venue .. " · " .. city,
          body = "Bâtiment non-fumeur. Merci de garder les espaces communs accueillants." },
        { c = "#d94d4d", label = "EN SALLE",           title = "Téléphones en silencieux",
          body = "Pas de nourriture en salle de conférence." },
        { c = "#c79a00", label = "JEU FIL ROUGE",      title = "Rassemblez-les tous !",
          body = "Trouvez les pièces du puzzle · puzzle@caen.tech" },
        { c = "#b15fa3", label = "VOS RETOURS COMPTENT", title = "Notez les sessions",
          body = "Pour les speakers et pour les prochaines éditions." },
        { c = "#6470c0", label = "REJOIGNEZ LA COMMUNAUTÉ", title = "LinkedIn · @caentech",
          body = "Discord & WhatsApp" },
        { c = "#4f9f3e", label = "WI-FI",              title = "réseau · MoHo-Guest",
          body = "code · caentech2026" },
    }

    local colW = (W - PAD * 2 - GAP * 2) / 3
    local rowH = (H - TOP - BOT - GAP) / 2

    for i, card in ipairs(cards) do
        local col = (i - 1) % 3
        local row = math.floor((i - 1) / 3)
        local x = PAD + col * (colW + GAP)
        local y = TOP + row * (rowH + GAP)

        -- popIn décalé
        local delay = (i - 1) * 0.06
        local p = math.max(0, math.min(1, (ctx.t - delay) / 0.5))
        local sc = 0.9 + 0.1 * (p * p * (3 - 2 * p))
        lg.push()
        lg.translate(x + colW / 2, y + rowH / 2)
        lg.scale(sc, sc)
        lg.translate(-(x + colW / 2), -(y + rowH / 2))

        local accent = theme.hex(card.c)
        ui.card(x, y, colW, rowH, 22, {
            fill = theme.color.white, bar = "top", barColor = accent, barSize = 6,
            border = theme.hex("#000000", 0.06), echo = accent, echoDx = 10, echoDy = 12,
        })
        local ix = x + 30
        ui.setColor(accent)
        ui.spacedText(theme.font(19, "bold"), card.label, ix, y + 26, 2, "left")
        local th = ui.textClamped(theme.font(29, "bold"), card.title, ix, y + 64, colW - 60,
            theme.color.ink, 2, 34)
        ui.setColor(theme.color.muted)
        lg.setFont(theme.font(19))
        lg.printf(card.body, ix, y + 64 + th + 12, colW - 60, "left")

        lg.pop()
    end
end

return M
