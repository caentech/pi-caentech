--
-- Écran de configuration (affiché avant la boucle d'interstices) :
--   * URL du programme (éditable)
--   * Forcer la mise à jour
--   * Heure : réelle / manuelle (+ réglage)
--   * Mode : priorité salle, salle de cet écran
--   * Démarrer le diaporama
-- Navigation clavier (↑/↓, Entrée, ←/→, saisie) et souris. Auto-démarrage kiosque.
--
local utf8   = require("utf8")
local theme  = require("src.theme")
local ui     = require("src.ui")
local clock  = require("src.clock")
local config = require("src.config")
local lg     = love.graphics

local M = {}

M.onStart = nil   -- callback défini par main

local cfg, prog
local sel = 1
local editing = false
local autostart = 15
local autostartCancelled = false

-- Descripteurs de champs.
local fields

local function applyTime()
    if cfg.timeMode == "manual" then clock.setManual(cfg.manualTime)
    else clock.manual = nil end
end

local function forceUpdate()
    prog.fetchFromUrl(cfg.programUrl)
end

local function start()
    config.save(cfg)
    applyTime()
    if M.onStart then M.onStart() end
end

local function buildFields()
    fields = {
        { kind = "text",   key = "programUrl", label = "URL du programme" },
        { kind = "action", label = "Forcer la mise à jour", act = forceUpdate,
          hint = "Télécharge le programme depuis l'URL ci-dessus" },
        { kind = "choice", key = "timeMode", label = "Heure",
          opts = { { "real", "Réelle" }, { "manual", "Manuelle" } } },
        { kind = "time",   key = "manualTime", label = "Heure manuelle",
          dim = function() return cfg.timeMode ~= "manual" end },
        { kind = "choice", key = "roomPriority", label = "Priorité d'affichage",
          opts = { { "all", "Tout" }, { "conference", "Conférence" }, { "auditorium", "Amphithéâtre" } } },
        { kind = "choice", key = "thisRoom", label = "Salle de cet écran",
          opts = { { "none", "Non affichée" }, { "conference", "Conférence" }, { "auditorium", "Amphithéâtre" } } },
        { kind = "action", label = "Démarrer le diaporama", act = start, primary = true },
    }
end

function M.load(configTable, programModel)
    cfg = configTable
    prog = programModel
    buildFields()
    sel = 1; editing = false
    autostart = tonumber(os.getenv("CAENTECH_AUTOSTART") or "15") or 15
    if os.getenv("CAENTECH_NO_AUTOSTART") then autostartCancelled = true end
    applyTime()
end

function M.update(dt)
    if not autostartCancelled then
        autostart = autostart - dt
        if autostart <= 0 then autostart = 0; start() end
    end
end

-- --- Entrées ------------------------------------------------------------------
local function cancelAuto() autostartCancelled = true end

local function cycleChoice(f, dir)
    local cur = cfg[f.key]
    local idx = 1
    for i, o in ipairs(f.opts) do if o[1] == cur then idx = i end end
    idx = ((idx - 1 + dir) % #f.opts) + 1
    cfg[f.key] = f.opts[idx][1]
    if f.key == "timeMode" then applyTime() end
end

function M.keypressed(key)
    cancelAuto()
    local f = fields[sel]
    if editing then
        if key == "return" or key == "kpenter" or key == "escape" then editing = false end
        if key == "backspace" then
            local s = cfg[f.key]
            local byteoffset = utf8.offset(s, -1)
            cfg[f.key] = byteoffset and s:sub(1, byteoffset - 1) or ""
        end
        return
    end
    if key == "up" then sel = ((sel - 2) % #fields) + 1
    elseif key == "down" then sel = (sel % #fields) + 1
    elseif key == "escape" then love.event.quit()
    elseif key == "left" then
        if f.kind == "choice" then cycleChoice(f, -1)
        elseif f.kind == "time" then clock.setManual(cfg[f.key]); clock.adjust(-5); cfg[f.key] = clock.label() end
    elseif key == "right" then
        if f.kind == "choice" then cycleChoice(f, 1)
        elseif f.kind == "time" then clock.setManual(cfg[f.key]); clock.adjust(5); cfg[f.key] = clock.label() end
    elseif key == "return" or key == "kpenter" then
        if f.kind == "text" then editing = true
        elseif f.kind == "action" then f.act() end
    end
end

function M.textinput(t)
    if editing then
        local f = fields[sel]
        cfg[f.key] = (cfg[f.key] or "") .. t
    end
end

-- géométrie des lignes (calculée au draw, réutilisée au clic)
local rowGeom = {}

function M.mousepressed(mx, my, button)
    cancelAuto()
    for i, g in ipairs(rowGeom) do
        if mx >= g.x and mx <= g.x + g.w and my >= g.y and my <= g.y + g.h then
            sel = i
            local f = fields[i]
            if f.kind == "action" then f.act()
            elseif f.kind == "text" then editing = true
            elseif f.kind == "choice" then cycleChoice(f, 1)
            elseif f.kind == "time" then clock.setManual(cfg[f.key]); clock.adjust(5); cfg[f.key] = clock.label() end
            return
        end
    end
    editing = false
end

-- --- Dessin -------------------------------------------------------------------
local function valueText(f)
    if f.kind == "text" then return cfg[f.key]
    elseif f.kind == "time" then return cfg[f.key]
    elseif f.kind == "choice" then
        for _, o in ipairs(f.opts) do if o[1] == cfg[f.key] then return o[2] end end
        return "?"
    end
    return ""
end

function M.draw(W, H, time)
    rowGeom = {}
    -- titre
    ui.setColor(theme.color.ink)
    lg.setFont(theme.font(58, "bold"))
    lg.print("Configuration", 120, 96)
    ui.setColor(theme.color.muted)
    ui.spacedText(theme.font(22, "bold"), "CAEN.TECH · SIGNAGE", 120, 168, 4, "left")

    -- statut programme (haut-droite)
    local st = prog.status
    local stColor = st.state == "ok" and theme.color.green
                 or st.state == "error" and theme.hex("#d94d4d")
                 or theme.color.gold
    ui.setColor(stColor)
    ui.spacedText(theme.font(20, "bold"), theme.upper(st.message), 0, 110, 2, "right", W - 120)

    -- panneau
    local px, py, pw = 120, 230, W - 240
    local rowH, rowGap = 86, 14
    local n = #fields
    local ph = n * rowH + (n - 1) * rowGap + 56
    ui.card(px, py, pw, ph, 28, { fill = theme.color.white, border = theme.hex("#000000", 0.06) })

    local rx, ry = px + 40, py + 30
    local innerW = pw - 80
    for i, f in ipairs(fields) do
        local y = ry + (i - 1) * (rowH + rowGap)
        local selected = (i == sel)
        local dim = f.dim and f.dim()
        rowGeom[i] = { x = px + 16, y = y - 6, w = pw - 32, h = rowH }

        if selected then
            ui.setColor(theme.hex("#fff6c2"))
            ui.rrect("fill", px + 16, y - 6, pw - 32, rowH, 16)
            ui.setColor(theme.color.yellow)
            ui.rrect("fill", px + 16, y - 6, 6, rowH, 3)
        end

        -- label
        ui.setColor(dim and theme.color.faint or theme.color.muted3)
        ui.spacedText(theme.font(19, "bold"), theme.upper(f.label), rx, y + 6, 2, "left")

        if f.kind == "action" then
            -- bouton à droite
            local bf = theme.font(24, "bold")
            local txt = f.label
            local bw = bf:getWidth(txt) + 56
            local bx = px + pw - 40 - bw
            ui.setColor(f.primary and theme.color.yellow or theme.hex("#1a1813", selected and 1 or 0.85))
            ui.rrect("fill", bx, y + 4, bw, 50, 14)
            ui.setColor(f.primary and theme.color.ink or theme.color.white)
            lg.setFont(bf)
            lg.print(txt, bx + 28, y + 14)
            if f.hint then
                ui.setColor(theme.color.faint)
                lg.setFont(theme.font(17))
                lg.print(f.hint, rx, y + 44)
            end
        else
            -- valeur
            local vf = theme.font(28, "bold")
            lg.setFont(vf)
            local val = valueText(f)
            ui.setColor(dim and theme.color.faint or theme.color.ink)
            if f.kind == "choice" then
                local vw = vf:getWidth(val)
                local vx = px + pw - 40 - vw - 50
                ui.setColor(theme.color.muted)
                lg.print("‹", vx - 4, y + 8)
                ui.setColor(dim and theme.color.faint or theme.color.ink)
                lg.print(val, vx + 28, y + 8)
                ui.setColor(theme.color.muted)
                lg.print("›", vx + 28 + vw + 12, y + 8)
            else
                -- texte / time : aligné à gauche après le label, tronqué
                local vx = rx + 360
                local maxw = px + pw - 40 - vx
                local shown = val
                while vf:getWidth(shown) > maxw and #shown > 0 do shown = shown:sub(2) end
                if shown ~= val then shown = "…" .. shown end
                lg.print(shown, vx, y + 8)
                if editing and selected and (math.floor(time * 2) % 2 == 0) then
                    lg.print("|", vx + vf:getWidth(shown), y + 8)
                end
            end
        end
    end

    -- pied : auto-démarrage + aide
    local fy = py + ph + 36
    if not autostartCancelled then
        ui.setColor(theme.color.gold)
        lg.setFont(theme.font(24, "bold"))
        lg.printf(string.format("Démarrage automatique dans %d s — touchez pour configurer", math.ceil(autostart)),
            0, fy, W, "center")
    else
        ui.setColor(theme.color.muted)
        lg.setFont(theme.font(20))
        lg.printf("↑/↓ naviguer · ←/→ changer · Entrée éditer/valider · Échap quitter",
            0, fy, W, "center")
    end
end

return M
