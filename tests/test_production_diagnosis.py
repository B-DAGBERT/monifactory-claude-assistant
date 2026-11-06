"""Diagnostics are contextual explanations, not commands or assertions of unseen topology."""

import unittest
from types import SimpleNamespace

from monifactory_assistant.production.diagnosis import diagnose
from monifactory_assistant.production.knowledge import Completeness, Freshness, KnowledgeSnapshot
from monifactory_assistant.production.planner import ProductionLot, StockLedger, StockSource, plan
from monifactory_assistant.production.recipes import Amount, Catalog
from tests.test_production_factories import (
    IRON,
    KEY,
    NOW,
    OTHER,
    PLATE,
    WORLD,
    captured_conflict,
    evidence,
    machine,
    recipe,
    snapshot,
)


class DiagnosisTests(unittest.TestCase):
    def test_real_knowledge_conflict_is_explained_without_reactivating_discarded_states(self):
        report = self.report(captured_conflict())
        self.assertIn("machine_observation_conflict", self.codes(report))
        self.assertNotIn("machine_working", self.codes(report))
        self.assertNotIn("machine_waiting", self.codes(report))
        conflict = next(f for f in report.findings if f.code == "machine_observation_conflict")
        self.assertEqual(len(conflict.contradictions), 2)
        self.assertTrue(conflict.evidence)

    def test_real_plan_keeps_expected_output_separate_and_labels_stale_stock(self):
        calculation = plan(
            Catalog(3, (recipe(),)),
            Amount(PLATE, 4),
            StockLedger(
                sources=(StockSource("chest", (Amount(IRON, 2),)),),
                expected=(ProductionLot("pending", (Amount(PLATE, 1),)),),
            ),
        )
        report = self.report(
            snapshot(),
            planning=calculation,
            planning_world=WORLD,
            planning_generation=3,
            planning_evidence=(evidence(freshness=Freshness.STALE),),
        )
        self.assertEqual(calculation.missing, (Amount(IRON, 1),))
        self.assertEqual(calculation.waiting, (Amount(PLATE, 1),))
        shortage = next(f for f in report.findings if f.code == "ingredient_shortfall")
        self.assertEqual(shortage.certainty, "calculated")
        self.assertTrue(any("périmés" in item for item in shortage.missing))
        self.assertIn("awaiting_receipt", self.codes(report))

    def report(self, knowledge, selected=None, **kwargs):
        return diagnose(
            knowledge, recipe() if selected is None else selected, catalog_generation=3, **kwargs
        )

    def codes(self, report):
        return {finding.code for finding in report.findings}

    def test_no_observation_is_not_evidence_of_absence(self):
        report = self.report(snapshot())
        self.assertIn("machine_not_observed", self.codes(report))
        self.assertNotIn("machine_absence_observed", self.codes(report))
        self.assertTrue(all(f.certainty == "unknown" for f in report.findings))
        self.assertIn("ne prouve pas", report.next_action)

    def test_missing_world_or_foreign_records_fail_closed(self):
        report = self.report(KnowledgeSnapshot(None, NOW, (machine(),)))
        self.assertEqual(self.codes(report), {"world_unknown"})
        report = self.report(snapshot(machine(proof=evidence(world=OTHER))))
        self.assertNotIn("machine_compatible", self.codes(report))

    def test_explicit_live_server_states_are_reported_with_their_evidence(self):
        observed = machine(
            state={
                "s": "WAITING",
                "r": "Not enough steam",
                "f": False,
                "vap": [0, 16000],
                "se": True,
            }
        )
        report = self.report(snapshot(observed))
        self.assertTrue(
            {"machine_waiting", "multiblock_unformed", "vap_empty", "boiler_dry"}
            <= self.codes(report)
        )
        for finding in report.findings:
            if finding.certainty == "observed":
                self.assertEqual(finding.evidence, observed.evidence)
        self.assertTrue(all(f.certainty == "hypothesis" for f in report.hypotheses))
        self.assertTrue(all("câble" not in f.message for f in report.findings))

    def test_historical_or_prior_session_states_are_not_current_faults(self):
        for proof in (
            evidence(freshness=Freshness.STALE),
            evidence(freshness=Freshness.HISTORICAL),
            evidence(session="b" * 32),
            evidence(received_at_ms=NOW + 1),
        ):
            with self.subTest(proof=proof):
                report = self.report(
                    snapshot(machine(state={"f": False, "s": "WAITING"}, proof=proof))
                )
                self.assertIn("machine_historical", self.codes(report))
                self.assertNotIn("multiblock_unformed", self.codes(report))
                self.assertNotIn("machine_waiting", self.codes(report))

    def test_presence_compatibility_and_live_status_remain_separate(self):
        report = self.report(snapshot(machine()))
        self.assertIn("machine_compatible", self.codes(report))
        self.assertIn("machine_status_unknown", self.codes(report))
        report = self.report(snapshot(machine(block="gtceu:hv_bender")), machine_key=KEY)
        self.assertIn("machine_compatibility_unknown", self.codes(report))
        self.assertNotIn("machine_compatible", self.codes(report))

    def test_circuit_configuration_is_unknown_until_explicitly_observed(self):
        target = recipe(circuit=2)
        report = self.report(snapshot(machine(state={"s": "IDLE"})), target)
        self.assertIn("circuit_unknown", self.codes(report))
        self.assertNotIn("circuit_mismatch", self.codes(report))
        report = self.report(snapshot(machine(state={"circuit": 1})), target)
        self.assertIn("circuit_mismatch", self.codes(report))
        report = self.report(snapshot(machine(state={"circuit": 2})), target)
        self.assertNotIn("circuit_mismatch", self.codes(report))

    def test_absence_requires_complete_live_evidence(self):
        report = self.report(snapshot(machine(kind="absence")), machine_key=KEY)
        self.assertIn("machine_not_in_authorized_survey", self.codes(report))
        self.assertIn("droits d’accès", report.next_action)
        self.assertNotIn("machine_absence_observed", self.codes(report))
        report = self.report(snapshot(machine(kind="absence", absence_scope=None)), machine_key=KEY)
        self.assertIn("machine_absence_unproven", self.codes(report))
        report = self.report(
            snapshot(machine(kind="absence", proof=evidence(completeness=Completeness.PARTIAL))),
            machine_key=KEY,
        )
        self.assertIn("machine_absence_unproven", self.codes(report))

    def test_conflicting_live_records_require_a_check_not_a_guessed_cause(self):
        first = machine(state={"s": "WORKING"})
        second = machine(ident="other-source", state={"s": "WAITING"})
        report = self.report(snapshot(first, second))
        self.assertIn("machine_observation_conflict", self.codes(report))
        self.assertNotIn("machine_waiting", self.codes(report))
        self.assertNotIn("machine_working", self.codes(report))
        conflict = next(f for f in report.findings if f.code == "machine_observation_conflict")
        self.assertEqual(len(conflict.contradictions), 2)

    def test_working_and_unformed_in_same_packet_remains_an_explicit_contradiction(self):
        report = self.report(snapshot(machine(state={"s": "WORKING", "f": False})))
        contradiction = next(f for f in report.findings if f.code == "multiblock_unformed")
        self.assertTrue(contradiction.contradictions)

    def test_planner_context_cannot_cross_world_or_generation(self):
        plan = SimpleNamespace(generation=3, missing=(Amount(IRON, 2),), waiting=(), issues=())
        for world, generation in ((None, 3), (OTHER, 3), (WORLD, 4)):
            with self.subTest(world=world, generation=generation):
                report = self.report(
                    snapshot(), planning=plan, planning_world=world, planning_generation=generation
                )
                self.assertIn("planning_context_mismatch", self.codes(report))
                self.assertNotIn("ingredient_shortfall", self.codes(report))
        plan.generation = 2
        report = self.report(snapshot(), planning=plan, planning_world=WORLD, planning_generation=3)
        self.assertIn("planning_context_mismatch", self.codes(report))

    def test_shortfalls_and_pending_outputs_are_calculations_not_fabrication_proof(self):
        plan = SimpleNamespace(
            generation=3, missing=(Amount(IRON, 2),), waiting=(Amount(PLATE, 4),), issues=()
        )
        args = dict(planning=plan, planning_world=WORLD, planning_generation=3)
        unknown = self.report(snapshot(), **args)
        shortage = next(f for f in unknown.findings if f.code == "ingredient_shortfall")
        self.assertEqual(shortage.certainty, "unknown")
        report = self.report(snapshot(), planning_evidence=(evidence(),), **args)
        shortage = next(f for f in report.findings if f.code == "ingredient_shortfall")
        waiting = next(f for f in report.findings if f.code == "awaiting_receipt")
        self.assertEqual(shortage.certainty, "calculated")
        self.assertIn("réception n’est pas confirmée", waiting.message)
        self.assertTrue(waiting.missing)
        self.assertEqual(plan.waiting, (Amount(PLATE, 4),))

    def test_uninterpreted_conditions_and_invalid_status_do_not_become_proven_faults(self):
        target = recipe(unsupported=("cleanroom",))
        report = self.report(
            snapshot(
                machine(state={"s": "UNKNOWN", "f": "false", "se": "true", "eau": [False, 100]})
            ),
            target,
        )
        self.assertIn("recipe_conditions_unknown", self.codes(report))
        self.assertNotIn("multiblock_unformed", self.codes(report))
        self.assertNotIn("boiler_dry", self.codes(report))
        self.assertNotIn("eau_empty", self.codes(report))

    def test_reports_are_deterministic_and_do_not_modify_source_data(self):
        raw = {"s": "WAITING", "r": "Input missing"}
        observed = machine(state=raw)
        knowledge = snapshot(observed)
        first = self.report(knowledge)
        self.assertEqual(first, self.report(knowledge))
        self.assertEqual(raw, {"s": "WAITING", "r": "Input missing"})
        self.assertEqual(knowledge.observations, (observed,))
