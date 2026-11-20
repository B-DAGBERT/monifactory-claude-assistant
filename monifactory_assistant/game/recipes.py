"""Index local des recettes et des machines du pack."""

import json
import re
from collections import defaultdict


class RecipeIndex:
    """Les recettes du pack rangées par objet produit et par catégorie, et les machines qui font chaque catégorie,
    pour donner à Codex ce qui concerne les objets cités dans une question : comment les fabriquer et, pour une
    machine, ce qu'elle fait. Il n'a plus à le chercher lui-même (un aller-retour de 3 à 5 s de moins), et il sait
    à quoi sert une machine même quand ses recettes ne portent pas son nom (la Steam Foundry fait celles d'une autre
    catégorie)."""

    OUTPUT = re.compile(r"(?:[0-9]+x )?([a-z0-9_.-]+:[a-z0-9_./-]+)(?: \([0-9]+%\))?")
    ITEM_ID = re.compile(r"(?<![a-z0-9_.-])[a-z0-9_.-]+:[a-z0-9_./-]+")  # id cité tel quel
    # Formes principales d'un matériau (gtceu:chromium_ingot), de la préférée à la dernière
    FORMS = ("ingot", "dust", "gem")
    # Partie d'un ancien catalogue sans groupe (manifeste version 1 ou 2)
    LEGACY_PART = re.compile(r"recettes_[0-9]+\.json")

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
        self.joined = {}  # « tungstensteel » -> « tungsten_steel » : un matériau écrit en un mot
        self.group = None  # groupe lu (a ou b) d'un manifeste version 3
        self.pattern = None  # « recettes_a_*.json » : les parties lues, à chercher avec Grep
        if recipes_dir is None:
            return
        for line in self.catalog_lines(recipes_dir):
            self.by_category[line.partition(" | ")[0]].append(line)
            outputs = line.partition(" => ")[2].split(" | ")[0]
            matches = (self.OUTPUT.fullmatch(output.strip()) for output in outputs.split(" + "))
            for item in dict.fromkeys(match[1] for match in matches if match):
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
        for name in self.by_name:
            material, _, form = name.rpartition("_")
            if "_" in material and form in self.FORMS:
                self.joined.setdefault(material.replace("_", ""), material)

    def catalog_lines(self, folder):
        """Les recettes des parties que publie le manifeste. Retient leur groupe et le motif de leurs fichiers."""
        manifest = json.loads((folder / "recettes_info.json").read_text(encoding="utf-8"))
        if not isinstance(manifest, dict):
            raise ValueError("Manifeste du catalogue recettes invalide")
        parts, total = manifest.get("parties"), manifest.get("nombre")
        if type(parts) is not int or not 1 <= parts <= 4096 or type(total) is not int or total <= 0:
            raise ValueError("Nombre de recettes ou de parties invalide")
        group, generation = manifest.get("groupe"), manifest.get("generation")
        if manifest.get("version", 1) in (1, 2):
            prefix, group = "recettes_", None
        elif (
            manifest.get("version") == 3
            and group in ("a", "b")
            and isinstance(generation, str)
            and generation
        ):
            prefix = f"recettes_{group}_"
        else:
            raise ValueError("Génération du catalogue recettes invalide")
        self.group, self.pattern = group, f"{prefix}*.json"
        count = 0
        for part in range(parts):
            data = json.loads((folder / f"{prefix}{part}.json").read_text(encoding="utf-8"))
            if not isinstance(data, dict) or (
                generation is not None and data.get("generation") != generation
            ):
                raise ValueError("Partie de recette étrangère à la génération")
            lines = data.get("recettes")
            if not isinstance(lines, list) or any(not isinstance(line, str) for line in lines):
                raise ValueError("Partie du catalogue recettes invalide")
            count += len(lines)
            yield from lines
        if count != total:
            raise ValueError("Catalogue recettes incomplet")

    @property
    def catalog_files(self):
        """Où chercher les recettes : les parties lues, sinon le groupe actif que donne le manifeste."""
        return self.pattern or "les parties du groupe actif de recettes_info.json"

    def remove_legacy_parts(self, folder):
        """Supprime l'ancien catalogue sans groupe (recettes_0.json…) une fois un groupe a ou b lu : Grep le
        parcourait avec le groupe actif et pouvait en citer des recettes périmées. Les parties des groupes a et b
        restent, car l'export écrit le groupe inactif pendant que l'autre reste lu. Avec un manifeste version 1
        ou 2, ces fichiers sont le catalogue actif : rien n'est supprimé. Rend les noms supprimés."""
        if self.group not in ("a", "b"):
            return []
        removed = []
        try:
            paths = sorted(folder.iterdir())
        except OSError:
            return []
        for path in paths:
            if self.LEGACY_PART.fullmatch(path.name):
                try:
                    path.unlink()
                except OSError:
                    continue
                removed.append(path.name)
        return removed

    @staticmethod
    def preferred(ids):
        """Les ids d'un même nom, ceux de GregTech et de Minecraft d'abord."""
        return sorted(ids, key=lambda x: (not x.startswith(("gtceu:", "minecraft:")), x))

    def material_fluid(self, item):
        """gtceu:chromium à côté de gtceu:chromium_ingot : le fluide du matériau. Ses recettes le refondent ou
        recyclent des pièces, elles ne disent pas comment obtenir le matériau."""
        namespace, _, name = item.partition(":")
        return any(
            f"{namespace}:{name}_{form}" in self.by_name.get(f"{name}_{form}", ())
            for form in self.FORMS
        )

    def named(self, key, cited=()):
        """Les objets que désigne un nom : l'objet de ce nom, sinon le matériau de ce nom (« chromium »,
        « tungstensteel ») par son lingot et sa poudre, sinon sa poudre, sinon sa gemme. Le fluide homonyme du
        matériau ne compte que s'il est cité par son id."""
        exact = [
            item
            for item in self.preferred(self.by_name.get(key, ()))
            if item in cited or not self.material_fluid(item)
        ]
        if exact:
            return exact[:1]
        material = self.joined.get(key, key)
        ingot, dust, gem = (
            self.preferred(self.by_name.get(f"{material}_{form}", ()))[:1] for form in self.FORMS
        )
        return ingot + dust if ingot else dust or gem

    def items_in(self, text, limit=3):
        """Les objets du pack cités dans un texte (« Bronze Plate », « bronze plate », « chromium »), les noms
        longs d'abord. La poudre jointe au lingot d'un matériau ne prend que la place laissée par les autres."""
        text = text.lower()
        cited = set(self.ITEM_ID.findall(text))
        words = re.findall(r"[a-z0-9]+", text)
        found, dusts, used = [], [], set()
        for size in (4, 3, 2, 1):
            for i in range(len(words) - size + 1):
                if used & set(range(i, i + size)):
                    continue
                key = "_".join(words[i : i + size])
                if size == 1 and (len(key) < 4 or key in self.IGNORED):
                    continue
                items = self.named(key, cited)
                if not items and key.endswith("s"):  # « bronze gears » -> bronze_gear
                    items = self.named(key[:-1], cited)
                if items:
                    found.append(items[0])
                    dusts += items[1:]
                    used |= set(range(i, i + size))
        return list(dict.fromkeys(found + dusts))[:limit]

    def about(self, item):
        """Ce que le pack dit d'un objet : comment le fabriquer et, si c'est une machine, ce qu'elle fait.
        Le recyclage d'objets finis passe en dernier : il ne dit pas comment fabriquer un matériau."""
        lines = sorted(
            self.by_output.get(item, []),
            key=lambda r: (
                r.partition(" | ")[0].endswith("_recycling"),
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
            f"Ce que le pack dit des objets de ma question (déjà cherché dans {self.catalog_files} et "
            "machines_recettes.txt : inutile de le rechercher, sauf pour un autre objet) :\n"
            + "\n".join(parts)
        )
