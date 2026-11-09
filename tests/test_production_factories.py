"""Logical groups preserve player declarations without inventing physical installations."""

import unittest
from dataclasses import replace

from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.production.factories import (
    FactoryDefinition,
    MachineRef,
    project_factories,
    propose_functions,
    recipe_dependencies,
)
from monifactory_assistant.production.knowledge import (
    Completeness,
    Evidence,
    Freshness,
    KnowledgeSnapshot,
    KnowledgeStatus,
    Observation,
    SourceSnapshot,
    build_knowledge,
)
from monifactory_assistant.production.recipes import (
    Amount,
    Catalog,
    Ingredient,
    Output,
    Recipe,
    Resource,
)

WORLD = "m1_" + "a" * 64
OTHER = "m1_" + "b" * 64
SESSION = "a" * 32
DIM = "minecraft:overworld"
NOW = 100_000
KEY = DIM + "|1|64|2"
MACHINE = "gtceu:lv_bender"
IRON = Resource("item", "minecraft:iron_ingot", "item")
PLATE = Resource("item", "gtceu:iron_plate", "item")


def evidence(**changes):
    values = dict(
        source="monde.json",
        reference="frame-1/block-1",
        status=KnowledgeStatus.OBSERVED,
        freshness=Freshness.LIVE,
        completeness=Completeness.COMPLETE,
        world=WORLD,
        session=SESSION,
        dimension=DIM,
        observed_at_ms=NOW - 100,
        received_at_ms=NOW,
        schema_version=2,
    )
    return Evidence(**dict(values, **changes))


def machine(
    *,
    key=KEY,
    block=MACHINE,
    state=None,
    proof=None,
    ident="machine-1",
    kind="machine",
    absence_scope="authorized_survey",
):
    value = {"block": block}
    if kind == "absence" and absence_scope is not None:
        value["absence_scope"] = absence_scope
    if state is not None:
        value["state"] = state
    return Observation(ident, kind, key, value, (proof or evidence(),))


def snapshot(*observations, scope=None):
    return KnowledgeSnapshot(scope or WorldScope(WORLD, SESSION, DIM), NOW, observations)


def recipe(*, ident="gtceu:plate", resource=PLATE, inputs=None, **kwargs):
    return Recipe(
        ident,
        "gtceu:bender",
        (Ingredient((Amount(IRON, 1),)),) if inputs is None else inputs,
        (Output(Amount(resource, 1)),),
        machines=(MACHINE,),
        **kwargs,
    )


def factory(**changes):
    values = dict(
        id="metallurgy",
        world=WORLD,
        name="Métallurgie",
        function="Plaques",
        members=(MachineRef(KEY, MACHINE),),
        evidence=(
            evidence(
                source="joueur", reference="declaration-1", status=KnowledgeStatus.USER_CONFIRMED
            ),
        ),
    )
    return FactoryDefinition(**dict(values, **changes))


def captured_conflict():
    scope = WorldScope(WORLD, SESSION, DIM)
    return build_knowledge(
        scope,
        NOW,
        tuple(
            SourceSnapshot(
                "monde.json",
                {
                    **scope.fields(),
                    "dimension": DIM,
                    "t": NOW,
                    "zone": [0, 0, 0, 0],
                    "blocs": [{"p": [1, 64, 2], "b": MACHINE, "m": {"s": status}}],
                },
                reference=status,
                received_at_ms=NOW,
            )
            for status in ("WORKING", "WAITING")
        ),
    )


class FactoryTests(unittest.TestCase):
    def test_real_knowledge_merge_conflict_preserves_declared_group(self):
        knowledge = captured_conflict()
        self.assertTrue(knowledge.history)
        (view,) = project_factories(knowledge, (factory(),))
        self.assertEqual(view.members[0].state, "conflict")
        self.assertEqual(view.definition.members, factory().members)

    def test_declaration_requires_world_and_explicit_player_provenance(self):
        for invalid in (
            {"world": "legacy"},
            {"evidence": ()},
            {"evidence": (evidence(status=KnowledgeStatus.DERIVED),)},
            {"evidence": (evidence(status=KnowledgeStatus.USER_CONFIRMED, world=OTHER),)},
            {"members": (MachineRef(KEY, MACHINE),) * 2},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                factory(**invalid)

    def test_unseen_absent_or_replaced_machine_does_not_delete_declared_group(self):
        group = factory()
        original = group
        cases = (
            (snapshot(), "unknown"),
            (snapshot(machine()), "observed"),
            (snapshot(machine(kind="absence")), "not_in_authorized_survey"),
            (snapshot(machine(kind="absence", absence_scope=None)), "unknown"),
            (snapshot(machine(block="gtceu:lv_wiremill")), "replaced"),
            (snapshot(machine(proof=evidence(freshness=Freshness.HISTORICAL))), "historical"),
        )
        for knowledge, state in cases:
            with self.subTest(state=state):
                (view,) = project_factories(knowledge, (group,))
                self.assertEqual(view.members[0].state, state)
                self.assertIs(view.definition, original)
                self.assertFalse(view.physical_connections_known)

    def test_conflicts_and_partial_absence_are_not_reconciled_by_guessing(self):
        (view,) = project_factories(
            snapshot(machine(), machine(kind="absence", ident="absence")), (factory(),)
        )
        self.assertEqual(view.members[0].state, "conflict")
        (view,) = project_factories(
            snapshot(machine(kind="absence", proof=evidence(completeness=Completeness.PARTIAL))),
            (factory(),),
        )
        self.assertNotEqual(view.members[0].state, "absence_observed")

    def test_scope_is_mandatory_even_for_identical_positions(self):
        foreign = machine(proof=evidence(world=OTHER))
        self.assertEqual(
            project_factories(KnowledgeSnapshot(None, NOW, (foreign,)), (factory(),)), ()
        )
        (view,) = project_factories(snapshot(foreign), (factory(),))
        self.assertEqual(view.members[0].state, "unknown")
        other = factory(
            world=OTHER, evidence=(evidence(world=OTHER, status=KnowledgeStatus.USER_CONFIRMED),)
        )
        self.assertEqual(project_factories(snapshot(machine()), (other,)), ())

    def test_reconnection_cannot_make_previous_machine_status_live(self):
        scope = WorldScope(WORLD, "b" * 32, DIM)
        (view,) = project_factories(snapshot(machine(), scope=scope), (factory(),))
        self.assertEqual(view.members[0].state, "historical")

    def test_function_groups_are_proposals_of_explicit_capabilities(self):
        far = machine(key=DIM + "|1000|64|1000", ident="far")
        nearby_unknown = machine(key=DIM + "|2|64|2", block="gtceu:hv_bender", ident="near")
        groups = propose_functions(
            snapshot(machine(), far, nearby_unknown), Catalog(7, (recipe(),))
        )
        self.assertEqual(len(groups), 1)
        self.assertEqual(len(groups[0].members), 2)
        self.assertEqual(groups[0].observation_ids, ("far", "machine-1"))
        self.assertFalse(groups[0].confirmed)
        self.assertFalse(groups[0].physical_connection)
        self.assertEqual(groups[0].catalog_generation, 7)

    def test_recipe_edges_preserve_variants_units_catalysts_and_alternatives(self):
        alternative = Resource("item", "gtceu:steel_plate", "item")
        consumer = recipe(
            ident="gtceu:case",
            resource=Resource("item", "gtceu:case", "item"),
            inputs=(Ingredient((Amount(PLATE, 2), Amount(alternative, 1)), reusable=True),),
        )
        producer = recipe()
        different_variant = recipe(
            ident="gtceu:other_plate", resource=replace(PLATE, variant="special")
        )
        catalog = Catalog(3, (producer, consumer, different_variant))
        edges = recipe_dependencies(catalog, (producer.id, consumer.id, different_variant.id))
        self.assertEqual(len(edges), 1)
        self.assertEqual((edges[0].producer, edges[0].consumer), (producer.id, consumer.id))
        self.assertTrue(edges[0].conditional)
        self.assertTrue(edges[0].reusable)
        self.assertFalse(edges[0].physical_connection)

    def test_zero_probability_is_not_a_dependency_and_chance_is_conditional(self):
        consumer = recipe(ident="gtceu:case", inputs=(Ingredient((Amount(PLATE, 1),)),))
        zero = replace(recipe(), outputs=(Output(Amount(PLATE, 1), 0, 1),))
        self.assertEqual(
            recipe_dependencies(Catalog(1, (zero, consumer)), (zero.id, consumer.id)), ()
        )
        random = replace(zero, outputs=(Output(Amount(PLATE, 1), 1, 2),))
        (edge,) = recipe_dependencies(Catalog(2, (random, consumer)), (random.id, consumer.id))
        self.assertTrue(edge.conditional)

    def test_graph_budget_or_unknown_recipe_never_returns_a_silent_partial_graph(self):
        a = recipe()
        b = recipe(ident="gtceu:b", inputs=(Ingredient((Amount(PLATE, 1),)),))
        c = replace(b, id="gtceu:c")
        catalog = Catalog(1, (a, b, c))
        with self.assertRaises(ValueError):
            recipe_dependencies(catalog, (a.id, b.id, c.id), limit=1)
        with self.assertRaises(ValueError):
            recipe_dependencies(catalog, ("gtceu:unknown",))

    def test_definitions_and_proposals_cannot_claim_a_physical_connection(self):
        (view,) = project_factories(snapshot(machine()), (factory(),))
        with self.assertRaises(ValueError):
            replace(view, physical_connections_known=True)
