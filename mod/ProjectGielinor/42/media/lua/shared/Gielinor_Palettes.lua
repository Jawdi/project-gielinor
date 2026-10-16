-- The colours a Gielinor character can choose, as the game's character
-- designer offers them, in its order. Values from the Old School RuneScape
-- Wiki's Makeover page (skin, clothing and shoes as listed there; hair
-- sampled from its picture of the hair colour swatches).

GielinorPalettes = {
    hair = {
        0x54421d, 0xe4e1e1, 0x86907c, 0x484444, 0xd9944f, 0xd6b964,
        0xa67b3c, 0x7d5a39, 0x129da2, 0x12a218, 0xb45015, 0xc718d0,
        0x2d2b2b, 0x796d60, 0xbbba95, 0xda9266, 0x90adca, 0x3f41ae,
        0xb59291, 0xcc655f, 0x571209, 0x92cb93, 0x3c5950, 0x8841b3,
        0xba8dc9, 0x242543, 0x9b0d05, 0xf8cb12, 0x38024a, 0x1f9b7f,
    },
    clothing = {
        0x7b6e32, 0x262323, 0x752f3b, 0x3b3b4e, 0xaa9045, 0xada481, 0x8b3b2a, 0x2d5272,
        0x2d723e, 0xa3970d, 0x83537b, 0x9f642f, 0xa17067, 0x829472, 0x728c94, 0x1c6135,
        0x101010, 0x404040, 0xf8d890, 0xf8be82, 0x9696ff, 0x0000ff, 0xffbebe, 0xc85032,
        0x952800, 0xc7fec7, 0x007878, 0x960096, 0xfe95fe,
    },
    shoes = {
        0x3c2412, 0x2a2901, 0x6b5839, 0x1c1919, 0x4e2b0f, 0x44443c,
    },
    -- The nine everyone has; the rest are unlocked in the game, so they're
    -- left out for now.
    skin = {
        0xd5a985, 0xbb8f6a, 0xa47f5d, 0x9e7a49, 0x836e42, 0x79582a, 0x644e24, 0x4c3d09, 0x6441a4,
    },
}

--- A palette colour as red, green and blue from 0 to 1.
function GielinorPalettes.rgb(value)
    return math.floor(value / 65536) % 256 / 255, math.floor(value / 256) % 256 / 255, value % 256 / 255
end
