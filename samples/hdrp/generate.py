#!/usr/bin/env python3
"""Writes the samples/hdrp fixture: a local HDRP (CentraXX) export, synthetic.

Run from anywhere: python3 samples/hdrp/generate.py. See README.md for what each part
models and where its codes and URLs come from.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
CXX = "urn:centraxx"
EXT = "https://fhir.centraxx.de/extension"
DAR = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
ORG_UNIT = "P-2216-NAP"

# Display names as the sample type catalogue spells them.
TYPES = {
    "BLD": "Blood, whole [BLD]",
    "PL1": "Plasma, single spun [PL1]",
    "SER": "Serum [SER]",
    "BFF": "Buffy coat, unficolled, non viable [BFF]",
    "SAL": "Saliva [SAL]",
    "ZZZ(pbm)": "Cells, blood mononuclear (PBMC), viable [ZZZ(pbm)]",
    "ZZZ(ppm)": "Cells, blood mononuclear (PBMC), pellet [ZZZ(ppm)]",
}


def patient(pid, sid, family, given, birth, gender):
    return {
        "resourceType": "Patient",
        "id": pid,
        "identifier": [{"type": {"coding": [{"system": CXX, "code": "SID"}]}, "value": sid}],
        "name": [{"family": family, "given": [given]}],
        "gender": gender,
        "birthDate": birth,
    }


def coding(code, system=CXX):
    return {"valueCoding": {"system": system, "code": code}}


def sprec(**fields):
    """The nested SPREC extension; each field becomes a sub-extension."""
    subs = [{"url": f"{EXT}/sprec/useSprec", "valueBoolean": True}]
    for name, code in fields.items():
        subs.append({"url": f"{EXT}/sprec/{name}", **coding(code)})
    return {"url": f"{EXT}/sprec", "extension": subs}


def location(path, x, y):
    return {
        "url": f"{EXT}/sample/sampleLocation",
        "extension": [
            {"url": f"{EXT}/sample/sampleLocationPath", "valueString": path},
            {"url": f"{EXT}/sample/xPosition", "valueInteger": x},
            {"url": f"{EXT}/sample/yPosition", "valueInteger": y},
        ],
    }


def specimen(sid, category, type_code, patient_id, sample_id, *, parent=None, ext_sample_id=None,
             receptacle, capacity, rest, unit="ml", collected=None, received=None, received_unknown=False,
             initial=None, derived=None, stored=None, place=None, sprec_fields=None, concentration=None):
    """One HDRP sample. Only a MASTER carries the organisation unit, the collection and
    the primary container; an aliquot has to reach its master for those."""
    identifiers = [{"type": {"coding": [{"system": CXX, "code": "SAMPLEID"}]}, "value": sample_id}]
    if ext_sample_id:
        identifiers.append({"type": {"coding": [{"system": CXX, "code": "EXTSAMPLEID"}]}, "value": ext_sample_id})
    resource = {
        "resourceType": "Specimen",
        "id": sid,
        "extension": [{"url": f"{EXT}/sampleCategory", **coding(category)}],
        "identifier": identifiers,
        "status": "available" if rest > 0 else "unavailable",
        "type": {"coding": [{"system": CXX, "code": type_code, "display": TYPES[type_code]}]},
        "subject": {
            "reference": f"Patient/{patient_id}",
            "identifier": {"type": {"coding": [{"system": CXX, "code": "SID"}]}, "value": PATIENTS[patient_id]},
        },
    }
    if parent:
        resource["parent"] = [{"reference": f"Specimen/{parent}"}]
    if received:
        resource["receivedTime"] = received
    elif received_unknown:
        resource["_receivedTime"] = {"extension": [{"url": DAR, "valueCode": "unknown"}]}
    if collected:
        resource["collection"] = {
            "collectedDateTime": collected,
            "quantity": {"value": initial, "unit": unit, "system": CXX},
        }
    resource["container"] = [{
        "identifier": [{"system": CXX, "value": receptacle}],
        "capacity": {"value": capacity, "unit": unit, "system": CXX},
        "specimenQuantity": {"value": rest, "unit": unit, "system": CXX},
    }]
    ext = resource["extension"]
    if category == "MASTER":
        ext.append({"url": f"{EXT}/sample/organizationUnit", "valueReference": {"identifier": {"value": ORG_UNIT}}})
    if derived:
        ext.append({"url": f"{EXT}/sample/derivalDate", "valueDateTime": derived})
    if stored:
        ext.append({"url": f"{EXT}/sample/repositionDate", "valueDateTime": stored})
    if place:
        ext.append(location(*place))
    if concentration:
        ext.append({"url": f"{EXT}/sample/concentration", "valueQuantity": concentration})
    if sprec_fields:
        ext.append(sprec(**sprec_fields))
    return resource


PATIENTS = {"2001": "SID-0002001", "2002": "SID-0002002"}
FREEZER = "Biobank --> Ultra-Tiefkühlschrank 3 -80°C --> Rack 2 --> Box 14"
TANK = "Biobank --> N2 Tank 1 -196°C --> Rack 5 --> Box 3"
DRAWN = "2026-03-02T08:10:00+01:00"
IN_LAB = "2026-03-02T09:05:00+01:00"
SPUN = "2026-03-02T10:30:00+01:00"


def aliquot(sid, type_code, patient_id, sample_id, parent, n, *, stored=True, rest=0.5, receptacle="Ma2D005ScT",
            capacity=0.5, box=FREEZER, **more):
    return specimen(sid, "DERIVED", type_code, patient_id, sample_id, parent=parent, receptacle=receptacle,
                    capacity=capacity, rest=rest, derived=SPUN,
                    stored="2026-03-02T11:15:00+01:00" if stored else None,
                    place=(box, n, 1) if stored else None, sprec_fields={"stockProcessing": "B(15)"}, **more)


def group(sid, type_code, patient_id, sample_id, parent, receptacle="Ma2D005ScT"):
    return specimen(sid, "ALIQUOTGROUP", type_code, patient_id, sample_id, parent=parent, receptacle=receptacle,
                    capacity=0.5, rest=0, derived=SPUN, sprec_fields={"stockProcessing": "B(15)"})


def master(sid, patient_id, sample_id, ext_id, type_code, container, receptacle="StMono075", capacity=7.5,
           **more):
    return specimen(sid, "MASTER", type_code, patient_id, sample_id, ext_sample_id=ext_id, receptacle=receptacle,
                    capacity=capacity, rest=0, collected=DRAWN, initial=capacity,
                    sprec_fields={"primarySampleContainer": container}, **more)


def observation(oid, specimen_id, patient_id, finding_date, lipaemic, icteric, haemolytic):
    def grade(code, value):
        return {
            "code": {"coding": [{"system": CXX, "code": code}]},
            "valueCodeableConcept": {"coding": [{"system": "urn:centraxx:CodeSystem/ValueList-412", "code": value}]},
        }
    return {
        "resourceType": "Observation",
        "id": oid,
        "status": "unknown",
        "code": {"coding": [{"system": CXX, "code": "MATCHAR"}]},
        "subject": {"reference": f"Patient/{patient_id}"},
        "specimen": {"reference": f"Specimen/{specimen_id}"},
        "effectiveDateTime": finding_date,
        "method": {"coding": [{"system": CXX, "version": "3", "code": "MATCHAR_VISUAL"}]},
        "component": [grade("LIPAEMIC", lipaemic), grade("ICTERIC", icteric), grade("HAEMOLYTIC", haemolytic)],
    }


masters = [
    master("30001", "2001", "LIMS-30001", "NAP-0100-01", "BLD", "PED", received=IN_LAB),
    master("30010", "2001", "LIMS-30010", "NAP-0100-02", "BLD", "SCI", received=IN_LAB),
    master("30020", "2001", "LIMS-30020", "NAP-0100-03", "BLD", "SST", received=IN_LAB),
    master("30030", "2001", "LIMS-30030", "NAP-0100-04", "SAL", "ORG", receptacle="StSali001", capacity=2.0,
           received_unknown=True),
    master("30040", "2002", "LIMS-30040", "NAP-0200-01", "BLD", "PED", received=IN_LAB),
]

groups = [
    group("30002", "PL1", "2001", "LIMS-30002", "30001"),
    group("30006", "BFF", "2001", "LIMS-30006", "30001", receptacle="Ma2D010ScT"),
    group("30011", "PL1", "2001", "LIMS-30011", "30010"),
    group("30021", "SER", "2001", "LIMS-30021", "30020"),
    group("30041", "ZZZ(pbm)", "2002", "LIMS-30041", "30040", receptacle="Ma2D010ScT"),
    group("30043", "ZZZ(ppm)", "2002", "LIMS-30043", "30042", receptacle="Ma2D010ScT"),
]

aliquots = [
    aliquot("30003", "PL1", "2001", "LIMS-30003", "30002", 1),
    aliquot("30004", "PL1", "2001", "LIMS-30004", "30002", 2),
    # Not stored yet: no reposition date, no location.
    aliquot("30005", "PL1", "2001", "LIMS-30005", "30002", 3, stored=False),
    aliquot("30007", "BFF", "2001", "LIMS-30007", "30006", 4, receptacle="Ma2D010ScT", capacity=1.0, rest=1.0),
    # The citrate plasma: PL1 like the EDTA plasma above, told apart only by the
    # primary container recorded on their master, two parents up.
    aliquot("30012", "PL1", "2001", "LIMS-30012", "30011", 5),
    aliquot("30013", "PL1", "2001", "LIMS-30013", "30011", 6, stored=False),
    aliquot("30022", "SER", "2001", "LIMS-30022", "30021", 7),
    aliquot("30023", "SER", "2001", "LIMS-30023", "30021", 8),
    # PBMC isolated from the blood, then aliquoted again as pellets: four hops from
    # a pellet back to its master.
    aliquot("30042", "ZZZ(pbm)", "2002", "LIMS-30042", "30041", 1, receptacle="Ma2D010ScT", capacity=1.0,
            rest=1.0, box=TANK, concentration={"value": 2.5, "unit": "10^6/ml"}),
    aliquot("30044", "ZZZ(ppm)", "2002", "LIMS-30044", "30043", 2, receptacle="Ma2D010ScT", capacity=1.0,
            rest=1.0, box=TANK),
    aliquot("30045", "ZZZ(ppm)", "2002", "LIMS-30045", "30043", 3, receptacle="Ma2D010ScT", capacity=1.0,
            rest=0, stored=False),
]


def write_json(path, resource):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(resource, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def xml_observation(obs):
    """The observation as FHIR XML, for the third import path."""
    def coding_xml(c, indent):
        version = f'{indent}  <version value="{c["version"]}"/>\n' if "version" in c else ""
        return (f'{indent}<coding>\n{indent}  <system value="{c["system"]}"/>\n{version}'
                f'{indent}  <code value="{c["code"]}"/>\n{indent}</coding>\n')
    components = "".join(
        "  <component>\n    <code>\n" + coding_xml(c["code"]["coding"][0], "      ") + "    </code>\n"
        "    <valueCodeableConcept>\n" + coding_xml(c["valueCodeableConcept"]["coding"][0], "      ")
        + "    </valueCodeableConcept>\n  </component>\n"
        for c in obs["component"]
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n<Observation xmlns="http://hl7.org/fhir">\n'
        f'  <id value="{obs["id"]}"/>\n  <status value="{obs["status"]}"/>\n'
        "  <code>\n" + coding_xml(obs["code"]["coding"][0], "    ") + "  </code>\n"
        f'  <subject>\n    <reference value="{obs["subject"]["reference"]}"/>\n  </subject>\n'
        f'  <effectiveDateTime value="{obs["effectiveDateTime"]}"/>\n'
        f'  <specimen>\n    <reference value="{obs["specimen"]["reference"]}"/>\n  </specimen>\n'
        "  <method>\n" + coding_xml(obs["method"]["coding"][0], "    ") + "  </method>\n"
        + components + "</Observation>\n"
    )


def main():
    bundle = {
        "resourceType": "Bundle",
        "type": "collection",
        "entry": [{"fullUrl": f"Patient/{p['id']}", "resource": p} for p in [
            patient("2001", PATIENTS["2001"], "Muster", "Erika", "1971-05-14", "female"),
            patient("2002", PATIENTS["2002"], "Beispiel", "Jonas", "1988-11-02", "male"),
        ]] + [{"fullUrl": f"Specimen/{m['id']}", "resource": m} for m in masters],
    }
    # 00- so it loads first: files load in path order, and the patients lead the pool.
    write_json(HERE / "00-bundle.json", bundle)
    for g in groups:
        write_json(HERE / "aliquot-groups" / f"{g['id']}.json", g)
    (HERE / "aliquots.ndjson").write_text(
        "".join(json.dumps(a, ensure_ascii=False) + "\n" for a in aliquots), encoding="utf-8")
    serum = observation("40001", "30022", "2001", "2026-03-02T12:00:00+01:00", "1+", "0", "0")
    plasma = observation("40002", "30003", "2001", "2026-03-02T12:05:00+01:00", "0", "0", "2+")
    (HERE / "observations").mkdir(exist_ok=True)
    (HERE / "observations" / "40001.xml").write_text(xml_observation(serum), encoding="utf-8")
    write_json(HERE / "observations" / "40002.json", plasma)


if __name__ == "__main__":
    main()
