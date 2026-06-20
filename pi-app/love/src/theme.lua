--
-- Thème Caen.Tech : palette (charte graphique + maquette interstice), polices Arimo,
-- couleurs de tracks (thème de session → couleur). Source : design system Caen.Tech
-- (tokens/colors.css) et la maquette « Interstice Caen.Tech.dc.html ».
--
local theme = {}

-- "#rrggbb" → {r, g, b, a} normalisé 0..1 (a optionnel, défaut 1).
function theme.hex(s, a)
    local r = tonumber(s:sub(2, 3), 16) / 255
    local g = tonumber(s:sub(4, 5), 16) / 255
    local b = tonumber(s:sub(6, 7), 16) / 255
    return { r, g, b, a or 1 }
end
local hex = theme.hex

-- --- Palette ------------------------------------------------------------------
theme.color = {
    cream      = hex("#eee9dc"),  -- fond global
    ink        = hex("#1a1813"),  -- texte fort
    ink2       = hex("#5a564c"),
    muted      = hex("#7a756a"),  -- texte secondaire
    muted2     = hex("#8a8577"),  -- labels
    muted3     = hex("#6f6c61"),
    faint      = hex("#b3ae9f"),  -- pauses / désactivé
    hair       = hex("#d6d1c2"),  -- filets
    white      = hex("#ffffff"),

    gold       = hex("#c79a00"),  -- accent or (texte)
    yellow     = hex("#ffdd00"),  -- jaune primaire
    green       = hex("#4f9f3e"), -- « EN COURS »

    -- pastilles salles
    roomConf   = hex("#ffdd00"),
    roomAmphi  = hex("#85ccd5"),
}

-- Accents et teintes vives par écran (ordre : programme, à suivre, infos, sponsors).
theme.accents = { hex("#c79a00"), hex("#3fa6b3"), hex("#4f9f3e"), hex("#b15fa3") }
theme.bright  = { hex("#ffdd00"), hex("#85ccd5"), hex("#92c784"), hex("#c78fbf") }

-- Thème de session (champ `theme` du JSON) → couleur de track.
-- Repris du TRACK de la maquette + charte ; défaut neutre sinon.
theme.track = {
    ["Cybersécurité & Résilience"]    = hex("#d94d4d"),
    ["Tech & Ingénierie Logicielle"]  = hex("#6470c0"),
    ["IA, Data & Automatisation"]     = hex("#4f9f3e"),
    ["UX / UI & Design Produit"]      = hex("#b15fa3"),
    ["Autres"]                        = hex("#3fa6b3"),
    ["Événement"]                     = hex("#c79a00"),
    ["Keynote"]                       = hex("#c79a00"),
    ["Networking"]                    = hex("#b3ae9f"),
}
theme.trackDefault = hex("#3fa6b3")

function theme.trackColor(name)
    return theme.track[name or ""] or theme.trackDefault
end

-- Majuscules tenant compte des accents français (string.upper ne gère que l'ASCII).
local accentUpper = {
    ["à"]="À",["â"]="Â",["ä"]="Ä",["á"]="Á",["è"]="È",["é"]="É",["ê"]="Ê",["ë"]="Ë",
    ["î"]="Î",["ï"]="Ï",["í"]="Í",["ì"]="Ì",["ô"]="Ô",["ö"]="Ö",["ó"]="Ó",["ò"]="Ò",
    ["õ"]="Õ",["û"]="Û",["ü"]="Ü",["ú"]="Ú",["ù"]="Ù",["ç"]="Ç",["ñ"]="Ñ",["œ"]="Œ",
}
function theme.upper(s)
    s = string.upper(s or "")
    return (s:gsub("[\194-\244][\128-\191]*", function(c) return accentUpper[c] or c end))
end

-- --- Polices ------------------------------------------------------------------
-- Arimo statique (Regular/Bold) bundlé ; repli sur la police par défaut LÖVE.
local fontCache = {}
local paths = {
    regular = "assets/fonts/Arimo-Regular.ttf",
    bold    = "assets/fonts/Arimo-Bold.ttf",
}
local available = {
    regular = love.filesystem.getInfo(paths.regular) ~= nil,
    bold    = love.filesystem.getInfo(paths.bold) ~= nil,
}

-- theme.font(taille, "bold"|"regular") — caché. Poids CSS 600/700 → bold.
function theme.font(size, weight)
    weight = (weight == "bold" or weight == 700 or weight == 600) and "bold" or "regular"
    local key = weight .. ":" .. size
    local f = fontCache[key]
    if not f then
        if available[weight] then
            f = love.graphics.newFont(paths[weight], size)
        else
            f = love.graphics.newFont(size)
        end
        f:setFilter("linear", "linear")
        fontCache[key] = f
    end
    return f
end

return theme
