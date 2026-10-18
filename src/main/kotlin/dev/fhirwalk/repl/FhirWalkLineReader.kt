package dev.fhirwalk.repl

import dev.fhirwalk.complete.MenuGroups
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal

/**
 * JLine's reader with the completion menu's groups in [MenuGroups.ORDER]. JLine has no
 * option for that, only this protected hook; it is made public so a test can check
 * the order the menu really uses.
 */
class FhirWalkLineReader(terminal: Terminal) : LineReaderImpl(terminal, "fhir-walk") {
    public override fun getGroupComparator(): Comparator<String> = MenuGroups.ORDER
}
