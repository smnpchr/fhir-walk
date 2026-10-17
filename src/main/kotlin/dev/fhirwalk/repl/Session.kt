package dev.fhirwalk.repl

import dev.fhirwalk.eval.Evaluator
import dev.fhirwalk.eval.PrefixWalker
import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.ModelNavigator
import dev.fhirwalk.pool.Importer
import dev.fhirwalk.eval.Focus
import dev.fhirwalk.eval.FocusResolver
import dev.fhirwalk.pool.ResourcePool

/**
 * Everything that depends on the chosen FHIR version, in one object.
 *
 * `:fhir r5` replaces this wholesale, which is why switching versions resets the
 * pool: a pool is homogeneous by construction. Mixed-version pools are the one
 * complexity we refuse outright.
 */
class Session(val version: FhirVersion) {
    val ctx = version.create()
    val pool = ResourcePool(ctx)
    val evaluator = Evaluator(ctx, pool)
    val navigator = ModelNavigator(ctx, pool.census)
    val prefixWalker = PrefixWalker(evaluator, pool)
    val render = Render(ctx)
    val importer = Importer(ctx, pool)
    val focusResolver = FocusResolver(evaluator, pool, navigator, render::typeOf)

    var focus: Focus? = null
}
