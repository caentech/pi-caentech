--
-- Fond statique épuré : aplat crème + une barre oblique jaune (bas-droite) au
-- dégradé doux sur les bords. Remplace l'ancienne couche d'ambiance animée
-- (dégradé radial + trame de points + blobs jaune/cyan) jugée trop chargée.
--
local background = {}

local CODE = [[
extern vec2 resolution;
// Travaille en coordonnées UV [0,1] (cadre 16:9), comme la maquette de référence.
// On dérive l'UV des screen_coords : love.graphics.rectangle ne fournit pas de
// texture_coords exploitables.
vec4 effect(vec4 col, Image tex, vec2 tc, vec2 sc) {
    // Repère y vers le haut (canvas : origine en bas).
    vec2 uv = vec2(sc.x / resolution.x, 1.0 - sc.y / resolution.y);
    vec3 cream  = vec3(0.933, 0.913, 0.863);
    vec3 yellow = vec3(1.0, 0.866, 0.0);

    // Barre oblique « / » du coin bas-droite : l'axe central traverse le bord bas
    // (x≈0.72) et ressort par le bord droit (y≈0.55) — repris de la maquette.
    vec2 P = vec2(0.72, 0.0);                 // ancre (bord bas)
    vec2 n = normalize(vec2(0.891, -0.454));  // normale à la barre
    float g = dot(uv - P, n);                 // distance signée à l'axe

    float halfW   = 0.075;   // demi-largeur du cœur jaune
    float feather = 0.055;   // dégradé latéral doux
    float band = 1.0 - smoothstep(halfW, halfW + feather, abs(g));

    // Fondu le long de la barre : pâle vers la pointe basse, plein en remontant.
    vec2 along = vec2(0.454, 0.891);
    float a = dot(uv - P, along);
    float ends = smoothstep(-0.2, 0.35, a);

    vec3 outc = mix(cream, yellow, band * ends);
    return vec4(outc, 1.0);
}
]]

local shader

function background.load()
    local ok, sh = pcall(love.graphics.newShader, CODE)
    if ok then shader = sh end
end

function background.draw(w, h, time)
    if not shader then
        love.graphics.setColor(0.933, 0.913, 0.863, 1)
        love.graphics.rectangle("fill", 0, 0, w, h)
        return
    end
    -- screen_coords sont en pixels : sur un canvas highdpi (dpiscale 2 sur Retina)
    -- la résolution réelle vaut 2× la taille logique. On envoie donc les dimensions
    -- pixel du canvas courant pour que l'UV reste borné à [0,1].
    local rw, rh = w, h
    local canvas = love.graphics.getCanvas()
    if canvas then rw, rh = canvas:getPixelDimensions() end
    love.graphics.setShader(shader)
    shader:send("resolution", { rw, rh })
    love.graphics.setColor(1, 1, 1, 1)
    love.graphics.rectangle("fill", 0, 0, w, h)
    love.graphics.setShader()
end

return background
