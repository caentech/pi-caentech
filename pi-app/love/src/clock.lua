--
-- Heure virtuelle : heure réelle du Pi + décalage réglable (minutes), ou heure
-- manuelle figée. Pilote l'horloge affichée et l'état « EN COURS / À suivre ».
-- (Reprend la logique de currentHeure() de l'ancien main.lua.)
--
local clock = {}

clock.offsetMinutes = 0   -- décalage appliqué à l'heure réelle
clock.manual = nil        -- si défini : minutes depuis minuit, heure figée

-- Minutes depuis minuit de l'heure réelle.
local function realNowMin()
    return tonumber(os.date("%H")) * 60 + tonumber(os.date("%M"))
end

-- Minutes depuis minuit de l'heure virtuelle courante (0..1439).
function clock.nowMinutes()
    local m
    if clock.manual then
        m = clock.manual
    else
        m = realNowMin() + clock.offsetMinutes
    end
    return ((m % 1440) + 1440) % 1440
end

-- "HH", "MM"
function clock.hhmm()
    local m = clock.nowMinutes()
    return string.format("%02d", math.floor(m / 60)), string.format("%02d", m % 60)
end

function clock.label()
    local hh, mm = clock.hhmm()
    return hh .. ":" .. mm
end

-- Règle l'heure manuelle depuis "HH:MM" (ou nil pour repasser en heure réelle).
function clock.setManual(str)
    if not str then clock.manual = nil; return end
    local h, m = str:match("^(%d%d?):(%d%d)$")
    if h then clock.manual = (tonumber(h) * 60 + tonumber(m)) % 1440 end
end

function clock.adjust(deltaMin)
    if clock.manual then
        clock.manual = ((clock.manual + deltaMin) % 1440 + 1440) % 1440
    else
        clock.offsetMinutes = clock.offsetMinutes + deltaMin
    end
end

return clock
