scoreboard players set #ok moni_atelier 0
execute store result score #planches moni_atelier run clear @s minecraft:oak_planks 0
execute store result score #etablis moni_atelier run clear @s minecraft:crafting_table 0
tellraw @s ["QA atelier - planches portees : ",{"score":{"name":"#planches","objective":"moni_atelier"}}," ; etablis portes : ",{"score":{"name":"#etablis","objective":"moni_atelier"}}]
