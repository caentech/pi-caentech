--
-- Helpers de dessin partagés (LÖVE 11.x) : couleurs, cartes + ombres, pastilles,
-- texte avec letter-spacing (non natif), photos rondes, masquage (stencil).
--
local utf8 = require("utf8")
local ui = {}

local lg = love.graphics

-- Applique une couleur {r,g,b,a} avec multiplicateur d'alpha optionnel.
function ui.setColor(c, am)
    lg.setColor(c[1], c[2], c[3], (c[4] or 1) * (am or 1))
end

-- Itère les caractères UTF-8 d'une chaîne.
local function chars(s)
    local out, i = {}, 1
    for _, code in utf8.codes(s) do
        out[#out + 1] = utf8.char(code)
    end
    return out
end

-- Largeur d'un texte avec tracking (espacement) manuel.
function ui.spacedWidth(font, s, tracking)
    local cs = chars(s)
    local w = 0
    for _, ch in ipairs(cs) do w = w + font:getWidth(ch) end
    return w + tracking * math.max(0, #cs - 1)
end

-- Dessine `s` avec un letter-spacing donné. align : "left"|"center"|"right" dans [x, x+areaW].
-- Renvoie la largeur dessinée.
function ui.spacedText(font, s, x, y, tracking, align, areaW)
    lg.setFont(font)
    local total = ui.spacedWidth(font, s, tracking)
    local cx = x
    if align == "center" then cx = x + (areaW - total) / 2
    elseif align == "right" then cx = x + (areaW - total) end
    for _, ch in ipairs(chars(s)) do
        lg.print(ch, cx, y)
        cx = cx + font:getWidth(ch) + tracking
    end
    return total
end

-- Texte multi-ligne tronqué à `maxLines` (… sur la dernière). Renvoie la hauteur dessinée.
function ui.textClamped(font, s, x, y, width, color, maxLines, lineH)
    lg.setFont(font)
    lineH = lineH or font:getHeight() * 1.16
    local _, lines = font:getWrap(s, width)
    local n = math.min(#lines, maxLines)
    ui.setColor(color)
    for i = 1, n do
        local line = lines[i]
        if i == maxLines and #lines > maxLines then
            -- tronque la dernière ligne visible avec une ellipse
            while font:getWidth(line .. "…") > width and #line > 0 do
                line = line:sub(1, -2)
            end
            line = line .. "…"
        end
        lg.print(line, x, y + (i - 1) * lineH)
    end
    return n * lineH
end

-- Rectangle arrondi (raccourci).
function ui.rrect(mode, x, y, w, h, r)
    lg.rectangle(mode, x, y, w, h, r, r)
end

-- Carte : écho de couleur décalé (à plat, sans flou) + remplissage + bordure fine
-- + barre (top|left) colorée optionnelle. L'écho remplace l'ancienne ombre douce :
-- on dessine une zone de couleur (accent), puis la carte blanche par-dessus, légèrement
-- décalée — la couleur ne dépasse alors que d'un bord (effet d'accent graphique).
-- opts = { fill, border, bar="top"|"left", barColor, barSize, echo, echoDx, echoDy }
function ui.card(x, y, w, h, r, opts)
    opts = opts or {}
    if opts.echo then
        -- écho décalé vers le bas-droite ; la carte blanche le recouvre sauf sur ces bords.
        local dx = opts.echoDx or 10
        local dy = opts.echoDy or 12
        ui.setColor(opts.echo)
        ui.rrect("fill", x + dx, y + dy, w, h, r)
    end
    ui.setColor(opts.fill or { 1, 1, 1, 1 })
    ui.rrect("fill", x, y, w, h, r)
    --[[
        if opts.bar then
            local bs = opts.barSize or 6
            ui.setColor(opts.barColor or { 0, 0, 0, 0.1 })
            if opts.bar == "left" then
                -- clip la barre dans le coin arrondi
                ui.pushClip(x, y, bs + r, h)
                ui.rrect("fill", x, y, bs + r, h, r)
                ui.popClip()
            else -- top
                ui.pushClip(x, y, w, bs + r)
                ui.rrect("fill", x, y, w, bs + r, r)
                ui.popClip()
            end
        end
        ]]
    if opts.border then
        ui.setColor(opts.border)
        lg.setLineWidth(1)
        ui.rrect("line", x + 0.5, y + 0.5, w - 1, h - 1, r)
    end
end

-- Pastille « pilule » (rayon plein). Renvoie sa largeur.
function ui.pill(s, font, x, y, padX, padY, bg, fg, tracking)
    tracking = tracking or 0
    local tw = ui.spacedWidth(font, s, tracking)
    local w = tw + padX * 2
    local h = font:getHeight() + padY * 2
    ui.setColor(bg)
    ui.rrect("fill", x, y, w, h, h / 2)
    ui.setColor(fg)
    ui.spacedText(font, s, x + padX, y + padY, tracking, "left")
    return w, h
end

-- Masque circulaire par shader (alpha) plutôt que par stencil : sur le GPU VideoCore
-- du Raspberry Pi (Mesa vc4), l'allocation paresseuse d'un tampon stencil sur le
-- canvas de rendu efface tout son contenu couleur (fond → transparent/noir). On
-- découpe donc la photo ronde en annulant l'alpha hors du disque, sans stencil.
-- `center`/`radius` sont en coordonnées du canvas (= coordonnées « stage » 1920×1080),
-- comme les screen_coords reçus par le shader lors du rendu dans le canvas.
local CIRCLE_MASK = [[
extern vec2 center;
extern float radius;
vec4 effect(vec4 color, Image tex, vec2 tc, vec2 sc) {
    vec4 c = Texel(tex, tc) * color;
    c.a *= 1.0 - smoothstep(radius - 1.5, radius + 1.5, distance(sc, center));
    return c;
}
]]
local circleShader

-- Photo ronde (masque shader) + anneau coloré. img peut être nil (cercle plein gris).
function ui.circlePhoto(img, cx, cy, radius, ringColor, ringW)
    ringW = ringW or 5
    if img then
        if circleShader == nil then
            local ok, sh = pcall(lg.newShader, CIRCLE_MASK)
            circleShader = ok and sh or false
        end
        local iw, ih = img:getDimensions()
        local s = (radius * 2) / math.min(iw, ih)
        if circleShader then
            circleShader:send("center", { cx, cy })
            circleShader:send("radius", radius)
            lg.setShader(circleShader)
        end
        ui.setColor({ 1, 1, 1, 1 })
        lg.draw(img, cx, cy, 0, s, s, iw / 2, ih / 2)
        lg.setShader()
    else
        ui.setColor({ 0.9, 0.88, 0.82, 1 })
        lg.circle("fill", cx, cy, radius)
    end
    -- anneau
    ui.setColor(ringColor)
    lg.setLineWidth(ringW)
    lg.circle("line", cx, cy, radius)
end

-- --- Clipping par pile de scissors (rectangulaire) ----------------------------
local clipStack = {}
function ui.pushClip(x, y, w, h)
    local prev = clipStack[#clipStack]
    local nx, ny, nw, nh = x, y, w, h
    if prev then
        -- intersection avec le scissor courant
        nx = math.max(x, prev[1]); ny = math.max(y, prev[2])
        local rx = math.min(x + w, prev[1] + prev[3])
        local ry = math.min(y + h, prev[2] + prev[4])
        nw = math.max(0, rx - nx); nh = math.max(0, ry - ny)
    end
    clipStack[#clipStack + 1] = { nx, ny, nw, nh }
    lg.setScissor(nx, ny, nw, nh)
end
function ui.popClip()
    clipStack[#clipStack] = nil
    local prev = clipStack[#clipStack]
    if prev then lg.setScissor(prev[1], prev[2], prev[3], prev[4]) else lg.setScissor() end
end

return ui
