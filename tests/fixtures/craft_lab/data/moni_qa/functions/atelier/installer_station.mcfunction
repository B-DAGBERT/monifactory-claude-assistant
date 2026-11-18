scoreboard objectives add moni_atelier dummy
fill -2 150 -2 4 150 3 minecraft:smooth_stone
setblock 1 151 0 minecraft:barrel
data modify block 1 151 0 Items set value [{Slot:0b,id:"minecraft:oak_planks",Count:8b}]
setblock 0 151 1 minecraft:crafting_table
execute if block 1 151 0 minecraft:barrel if data block 1 151 0 Items[{Slot:0b,id:"minecraft:oak_planks",Count:8b}] if block 0 151 1 minecraft:crafting_table run tellraw @s {"text":"QA atelier - station prete. Baril (1,151,0) : 8 planches. Etabli (0,151,1).","color":"green"}
