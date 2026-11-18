function moni_qa:machine/lire_poches
execute unless score @s moni_cible matches 63..65 run tellraw @s {"text":"Réglez moni_cible sur 63, 64 ou 65.","color":"red"}
execute if score @s moni_cible matches 63..65 if score @s moni_cuivre = @s moni_cible if score @s moni_plaques matches 0 run tellraw @s [{"text":"PASS stocks avant envoi : ","color":"green"},{"score":{"name":"@s","objective":"moni_cuivre"}},{"text":" cuivre, 0 plaque"}]
execute if score @s moni_cible matches 63..65 unless score @s moni_cuivre = @s moni_cible run tellraw @s [{"text":"FAIL cuivre attendu/physique : ","color":"red"},{"score":{"name":"@s","objective":"moni_cible"}},{"text":"/"},{"score":{"name":"@s","objective":"moni_cuivre"}}]
execute if score @s moni_cible matches 63..65 unless score @s moni_plaques matches 0 run tellraw @s [{"text":"FAIL plaques présentes avant envoi : ","color":"red"},{"score":{"name":"@s","objective":"moni_plaques"}}]
