# samples/hdrp

A local LIMS export in the shape IQVIA HDRP (formerly CentraXX) writes through its
FHIR custom export: two patients, their blood and saliva samples, the aliquots made
from them, and two material-characteristics findings. 26 resources, spread across all
three import paths: a bundle (`00-bundle.json`, which sorts first so the patients lead
the pool), a directory of JSON files (`aliquot-groups/`, `observations/40002.json`),
NDJSON (`aliquots.ndjson`) and XML (`observations/40001.xml`).

**Everything in it is synthetic.** No patient, sample, identifier or finding is real.
What is real is the shape: the profile's elements and extension URLs, and the
catalogue codes.

## What it models

```
Patient 2001
├── 30001  MASTER  BLD in a PED (EDTA) tube
│   ├── 30002  ALIQUOTGROUP  PL1 ── 30003, 30004, 30005 (not stored yet)
│   └── 30006  ALIQUOTGROUP  BFF ── 30007
├── 30010  MASTER  BLD in a SCI (citrate) tube
│   └── 30011  ALIQUOTGROUP  PL1 ── 30012, 30013 (not stored yet)
├── 30020  MASTER  BLD in an SST (serum) tube
│   └── 30021  ALIQUOTGROUP  SER ── 30022, 30023
└── 30030  MASTER  SAL, receipt time unknown
Patient 2002
└── 30040  MASTER  BLD in a PED tube
    └── 30041  ALIQUOTGROUP  ZZZ(pbm) ── 30042 PBMC isolate
        └── 30043  ALIQUOTGROUP  ZZZ(ppm) ── 30044, 30045 (pellets)
```

- **Only a master records its primary container** (`sprec/primarySampleContainer`)
  **and its organisation unit.** An aliquot reaches them through
  `parent.resolve().parent.resolve()`. This is the case the fixture exists for: the
  aliquots `30003` and `30012` are both `PL1`, plasma single spun, and only their
  masters say that one is EDTA plasma and the other citrate plasma. In the central
  NUM vocabulary that is `EDTA` versus `CIT`.
- **Coverage is uneven on purpose.** Three aliquots are not stored yet, so they have
  no `repositionDate` and no location. `collection` is only on masters. One master
  has no receipt time, only a data-absent-reason extension on `receivedTime`.
- **The material characteristics** (`MATCHAR`) are a lab finding in the shape of the
  HDRP Observation export: `code` is the finding, `method` the lab method, and each
  graded value (lipaemic, icteric, haemolytic) is a component.

`src/test/.../RegistryFixture.kt` builds a fhir-pipes registry from this export:
`master` holds it as it is, and `branch/out` holds it mapped to NUM. That includes a
first sync that maps every `PL1` aliquot to `EDTA`, and a re-application of the fixed
rule that changes only the citrate samples.

## Where the shape comes from

All from [kairos-fhir/kairos-fhir-dsl-mapping-example](https://github.com/kairos-fhir/kairos-fhir-dsl-mapping-example),
Apache-2.0, IQVIA's public examples for the HDRP FHIR custom export:

| what | from |
|---|---|
| Specimen elements, identifiers (`SAMPLEID`, `EXTSAMPLEID` in `urn:centraxx`), status from the remaining amount | `customexport/hdrp/v2/specimen.groovy`, `izi/leipzigLocal/specimen.groovy` |
| Extension URLs under `https://fhir.centraxx.de/extension` | `de/kairos/centraxx/fhir/r4/utils/FhirUrls.java` |
| Data-absent-reason on an unknown date | `izi/leipzigLocal/specimen.groovy` |
| Local sample type codes and their display names | `izi/leipzigLocal/xml/SampleType.xml` |
| Receptacles (`StMono075`, `StSali001`, `Ma2D005ScT`, `Ma2D010ScT`), primary containers (`PED`, `SCI`, `SST`), processing (`B(15)`), organisation unit `P-2216-NAP` → `NUM_Hannover`, and the mapping to NUM sample types | `hdrp/napkon/hub/specimen.groovy` (HUB Hannover to DZHK Greifswald) |
| Observation shape | `hdrp/v2/observation.groovy`, `hdrp/napkon/hub/observation.groovy` |

Not from there, and so invented: the `MATCHAR` finding, its method and its
component codes and grades, and the value list id. The sample type displays are
included for readability; the export mappings do not emit them. The `ZZZ(ppm)`
display is a guess, since the catalogue extract does not list that code. Observations
reference their specimen by id here; the HUB mapping references it by identifier.

## Regenerating

```
python3 samples/hdrp/generate.py
```

Then `./gradlew test --rerun-tasks`: `samples/` is not a declared input of the test
task, so a plain `./gradlew test` would report the previous run.
