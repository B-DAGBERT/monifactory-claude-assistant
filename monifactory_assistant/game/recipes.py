"""Index local des recettes et des machines du pack."""

import re
from collections import defaultdict


class RecipeIndex:
    """Les recettes du pack rangées par objet produit et par catégorie, et les machines qui font chaque catégorie,
    pour donner à Codex ce qui concerne les objets cités dans une question : comment les fabriquer et, pour une
    machine, ce qu'elle fait. Il n'a plus à le chercher lui-même (un aller-retour de 3 à 5 s de moins), et il sait
    à quoi sert une machine même quand ses recettes ne portent pas son nom (la Steam Foundry fait celles d'une autre
    catégorie)."""

    # Mots de la question qui ne sont jamais à eux seuls un nom d'objet
    IGNORED = {
        "comment",
        "pour",
        "avec",
        "dans",
        "faire",
        "fais",
        "fabrique",
        "fabriquer",
        "quoi",
        "quel",
        "quelle",
        "est",
        "que",
        "qui",
        "les",
        "des",
        "une",
        "mon",
        "mes",
        "ton",
        "tes",
        "sur",
        "par",
        "plus",
        "moins",
        "peux",
        "faut",
        "trouve",
        "where",
        "what",
        "how",
        "make",
        "the",
        "and",
    }

    def __init__(self, recipes_dir=None):
        self.by_output = defaultdict(
            list
        )  # « gtceu:bronze_plate » -> lignes « catégorie | entrées => sorties | id »
        self.by_category = defaultdict(list)  # « gtceu:alloy_smelter » -> ses recettes
        self.by_name = defaultdict(set)  # « bronze_plate » -> {« gtceu:bronze_plate »}
        self.machine_of = defaultdict(list)  # machine -> [(catégorie, nom, nombre de recettes)]
        self.machines = defaultdict(list)  # catégorie -> machines qui la font
        if recipes_dir is None:
            return
        for path in recipes_dir.glob("recettes_*.json"):
            if path.stem == "recettes_info":
                continue
            for line in path.read_text(encoding="utf-8").splitlines():
                line = line.strip().strip('",')
                self.by_category[line.partition(" | ")[0]].append(line)
                outputs = line.partition(" => ")[2].split(" | ")[0]
                for output in outputs.split(" + ")[:1]:
                    item = output.strip().split(" ")[-1]
                    if ":" in item:
                        self.by_output[item].append(line)
                        self.by_name[item.split(":")[-1]].add(item)
        machines = (
            recipes_dir / "machines_recettes.txt"
        )  # écrit par le mod : quelle machine fait quelles recettes
        if machines.exists():
            for line in machines.read_text(encoding="utf-8").splitlines():
                parts = line.split(" | ")
                if line.startswith("#") or len(parts) < 4:
                    continue
                category, name, count = parts[0], parts[1], parts[2].split(" ")[0]
                for machine in filter(None, (m.strip() for m in parts[3].split(","))):
                    self.machine_of[machine].append((category, name, count))
                    self.machines[category].append(machine)
                    self.by_name[machine.split(":")[-1]].add(machine)

    def items_in(self, text, limit=3):
        """Les objets du pack cités dans un texte (« Bronze Plate », « bronze plate »), les noms longs d'abord."""
        words = re.findall(r"[a-z0-9]+", text.lower())
        found, used = [], set()
        for size in (4, 3, 2, 1):
            for i in range(len(words) - size + 1):
                if used & set(range(i, i + size)):
                    continue
                key = "_".join(words[i : i + size])
                if size == 1 and (len(key) < 4 or key in self.IGNORED):
                    continue
                if key not in self.by_name and key.endswith("s"):  # « bronze gears » -> bronze_gear
                    key = key[:-1]
                ids = sorted(
                    self.by_name.get(key, ()),
                    key=lambda x: (not x.startswith(("gtceu:", "minecraft:")), x),
                )
                if ids:
                    found.append(ids[0])
                    used |= set(range(i, i + size))
        return found[:limit]

    def about(self, item):
        """Ce que le pack dit d'un objet : comment le fabriquer et, si c'est une machine, ce qu'elle fait."""
        lines = sorted(
            self.by_output.get(item, []),
            key=lambda r: (
                not r.startswith(("minecraft:crafting", "minecraft:smelting")),
                "nuclearcraft" in r,
                len(r),
            ),
        )
        text = (
            f"{item} se fabrique ainsi :\n" + "\n".join(f"  {r}" for r in lines[:6])
            if lines
            else ""
        )
        jobs = [
            j
            for j in self.machine_of.get(item, [])
            if not j[0].endswith(("multiblock_info", "_recycling"))
        ]
        if jobs:
            text += (
                f"\n{item} est une machine : elle fait les recettes des catégories suivantes (une recette de la "
                "catégorie gtceu:alloy_smelter se fait dans toutes les machines de cette catégorie) :"
            )
            for category, name, count in jobs[:4]:
                examples = sorted(self.by_category.get(category, []), key=len)[:6]
                text += f"\n  « {name} » ({category}, {count} recettes), par exemple :" + "".join(
                    f"\n    {r}" for r in examples
                )
        return text.strip()

    def hint(self, text):
        """Ce qui concerne les objets cités, prêt à joindre à la question (l'établi et le four d'abord)."""
        parts = [part for part in map(self.about, self.items_in(text)) if part]
        if not parts:
            return ""
        return (
            "Ce que le pack dit des objets de ma question (déjà cherché dans recettes_*.json et "
            "machines_recettes.txt : inutile de le rechercher, sauf pour un autre objet) :\n"
            + "\n".join(parts)
        )
