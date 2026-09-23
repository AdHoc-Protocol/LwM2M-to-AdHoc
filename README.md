# LwM2M-to-AdHoc — OMA LwM2M objects → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Converts [OMA LwM2M](https://www.openmobilealliance.org/release/LightweightM2M/) object definitions (the XML files of
the OMNA registry) into an [AdHoc](https://github.com/AdHoc-Protocol) protocol-description `.cs` file. The whole
registry (392 objects, ~4 500 resources) becomes one descriptor; a single object file becomes a descriptor of its own.

The project is self-contained: converter, emitter helpers, sample fetcher, build and validation scripts all live here.

## Links

| What | Where |
|:--|:--|
| OMNA LwM2M object registry (the inputs, one XML per object id) | https://github.com/OpenMobileAlliance/lwm2m-registry |
| XML schema of an object definition | https://github.com/OpenMobileAlliance/lwm2m-registry/blob/prod/LWM2M.xsd |
| Example object: 3303 Temperature | https://github.com/OpenMobileAlliance/lwm2m-registry/blob/prod/3303.xml |
| OMA registries overview page | https://www.openmobilealliance.org/specifications/registries/ |
| LwM2M specification releases | https://www.openmobilealliance.org/release/LightweightM2M/ |
| LwM2M Core 1.2.2 (HTML) | https://www.openmobilealliance.org/release/LightweightM2M/V1_2_2-20240613-A/HTML-Version/OMA-TS-LightweightM2M_Core-V1_2_2-20240613-A.html |
| Developer resources and issue tracker | https://github.com/OpenMobileAlliance/OMA_LwM2M_for_Developers |
| AdHoc protocol description format | https://github.com/AdHoc-Protocol |

## Layout

| Path | Contents |
|:--|:--|
| `src/org/unirail/LwM2M2AdHoc.java` | The converter (Java 17, `javax.xml` DOM only) |
| `src/org/unirail/adhoc/AdHocWriter.java`, `Json.java` | Local copy of the AdHoc emitter helpers (naming, docs, Dashboard, hosts, attributes) |
| `fetch-samples.sh` | Shallow-clones the registry into `samples/` (395 XML files + XSDs) |
| `build.sh` | Compiles and runs the converter over `samples/` into `AdHoc/` |
| `validate.sh` | Runs AdHocAgent in local parse-only mode over `AdHoc/*.cs` |
| `AdHoc/LwM2M.cs` | Generated descriptor of the full registry (2 MB) |
| `AdHoc/LwM2M_Temperature.cs` | Generated descriptor of a single object (`samples/3303.xml`) |

## What gets generated

```csharp
namespace org.lwm2m {
    /** <see cref='Device'/>  <see cref='Device.Reboot_Execute'/> … */   // Packs Inventory; AdHocAgent assigns the ids
    public interface LwM2M {
        enum _DefaultMaxLengthOf { Arrays = 65_535, Maps = 65_535, Sets = 65_535, Strings = 65_535, }

        class ObjectLink { ushort object_id; ushort instance_id; }             // Objlnk value pack
        class Blob { [D(65535)] Binary[,,] bytes; }                            // element of multi-instance Opaque lists
        class DurationInSeconds : Duration { public TimeSpan precision => TimeSpan.FromSeconds(1); }

        /** Device object description */
        class Device {
            public const ushort lwm2m_object_id = 3;  …urn, versions, multiple_instances, mandatory…
            [ResourceId(0), Operations("R")] string Manufacturer;
            [MinMax(0, 4), ResourceId(2), Mandatory] byte Security_Mode;       // integer RangeEnumeration → MinMax
            [ResourceId(1), Operations("RW"), Units("s")] DurationInSeconds Lifetime;  // elapsed time → Duration
            [ResourceId(13), Operations("R"), Mandatory] DateTime? Current_Time;

            /** Reboot the LwM2M Device … Execute operation on resource 4 */
            [ResourceId(4), Operations("E")] public class Reboot_Execute { [D(+1024)] string? execute_arguments; }
        }
        …
        struct Server : Host { }   struct Client : Host { }
        interface Management : Connects<Server, Client> {
            [_____lr_____<( …every object pack… )>] struct Objects { }         // Read / Write / Observe / Notify payloads
            [l____________<( …every *_Execute pack… )>] struct Execute { }      // Execute is Server → Client only
        }
        …ResourceId / Operations / Units / Range / Mandatory attribute declarations…
    }
}
```

## Mapping

| LwM2M XML | AdHoc | Notes |
|:--|:--|:--|
| `<Object ObjectType="MODefinition">` | `class <Name>` (transmittable pack) | name via `ident()`; on a collision the ObjectID is appended |
| `ObjectID` | `const ushort lwm2m_object_id` inside the pack | **not** pinned into the Dashboard: the ObjectID addresses the *source's* `/3/0/1` tree, while a pack id is AdHoc's own internal matter that AdHocAgent assigns and maintains |
| `ObjectURN`, `LWM2MVersion`, `ObjectVersion`, object `MultipleInstances`, `Mandatory` | `const` members of the pack | instance multiplicity is an application-level concern, kept as metadata |
| `Description1` + `Description2` | doc comment of the pack | |
| `<Item ID="n">` with `Operations` R / W / RW / empty | field of the pack | `[ResourceId(n)]`, `[Operations("RW")]`, `[Units("…")]`, `[Mandatory]` |
| `<Item>` with `Operations` E | nested empty command pack `<Resource>_Execute` with an optional `execute_arguments` string | sent Server → Client in the `Execute` state |
| `Type` Integer / Unsigned Integer | `long` / `ulong`; with an integer `RangeEnumeration` `a..b` the smallest fitting type (`byte`, `ushort`, `int`, …) plus `[MinMax(a, b)]` when the range is narrower than the type | `MinMax` is skipped for `a..a` and inverted ranges (AdHoc rejects them); hex ranges (`0x0000..0xFFFF`) are understood |
| an integer resource that **measures elapsed time** (`Lifetime`, `Minimum/Maximum Period`, `Timeout`, `Uptime`, `Interval`, `TTL`, … with time units or none) | a `class DurationIn<Unit> : Duration { precision; }` alias | AdHoc models an elapsed duration natively, so it replaces the bare integer. One alias per precision (`s`, `ms`, `min`, `h`); the generator sizes the container and expands `max` to fill it. A "period" counted in something other than time stays an integer |
| `String`, `Opaque` and multi-instance defaults | `enum _DefaultMaxLengthOf { … = 65_535 }` once per file | the registry states no ceiling for instance counts or blobs, and AdHoc's 255 default is far too small; a resource whose range gives a length still gets its own `[D]` |
| `Type` Float | `double` | LwM2M does not fix the precision |
| `Type` Boolean | `bool` | |
| `Type` String | `string`, `[D(+N)]` when the range gives a length (`0..255`, `32 bytes`, `1..64 bytes`, `16,32,48`) | default AdHoc limit 255 chars otherwise |
| `Type` Corelnk | `[D(+4096)] string` | CoRE link-format text |
| `Type` Opaque (or empty on a non-executable resource) | `[D(N)] Binary[,,]`, N from the range, else 65535 | |
| `Type` Time | `DateTime` | a wall-clock timestamp, which AdHoc models natively. LwM2M carries Unix seconds, AdHoc a 64-bit millisecond timestamp: convert at the application boundary |
| `Type` Objlnk | `ObjectLink` value pack (`ushort object_id; ushort instance_id`) | |
| `MultipleInstances` Multiple | `T[,,]` list (up to 65 535 instances via `_DefaultMaxLengthOf`); Opaque → `Blob[,,]` | a typedef of a collection cannot be a list element, hence the `Blob` sub-pack |
| `Mandatory` Optional | `T?` for value types | reference types (`string`, `Binary`, lists) are optional by nature; `[Mandatory]` marks mandatory ones |
| `RangeEnumeration` that is not an integer range or a length | `[Range("…")]` text attribute | float ranges, enumerations such as `10,26`, prose |
| `Units` | `[Units("…")]` | SenML unit strings as written in the registry |
| `Common.xml`, `DDF.xml`, `LWM2M_senml_units.xml` | skipped | no `MODefinition` inside |

## Build, run, validate

Java 17+ and git are required; AdHocAgent (Debug build with a personal UUID in its `AdHocAgent.toml`) for validation.

```bash
./fetch-samples.sh                                        # samples/ ← github.com/OpenMobileAlliance/lwm2m-registry
./build.sh                                                # AdHoc/LwM2M.cs from the whole samples/ folder
./build.sh samples/3303.xml                               # AdHoc/LwM2M_Temperature.cs from one object
./validate.sh AdHoc                                       # AdHocAgent, ADHOC_PARSE_ONLY=1, nothing is uploaded

# by hand:
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.LwM2M2AdHoc <object.xml | folder> [output folder]
```

## Validation result (registry snapshot of 2026-09-22)

| File | Content | AdHocAgent parse-only |
|:--|:--|:--|
| `AdHoc/LwM2M.cs` | 392 objects, 4 502 resources, 395 executable resources, 2 MB | **OK** — no errors, no warnings |
| `AdHoc/LwM2M_Temperature.cs` | object 3303 | **OK** |

AdHoc vocabulary used: 428 `[MinMax]` fields from integer ranges, 140 `Duration`-alias fields for elapsed times,
237 `DateTime` fields, one `_DefaultMaxLengthOf` per file. No `id = '` appears in either file — every pack id is
AdHocAgent's to assign. The branch dump (`AdHoc/LwM2M.branches.txt`) shows the `Objects` state carrying all 392
object packs on both sides and the `Execute` state carrying all 395 command packs Server → Client; `ObjectLink`,
`Blob` and the `Duration` aliases stay non-transmittable.

## Limitations

- The topology (one Server, one Client, one connection) is an invention of this converter; LwM2M itself runs over
  CoAP/TLS/DTLS with Bootstrap, Registration and Device-Management interfaces that are not modelled.
- Object instances (`/3/0`, `/3303/1`, …) and Read/Write/Observe semantics are not represented; every object pack is
  simply transmittable both ways. Object-level `MultipleInstances` and `Mandatory` are constants only.
- Executable resources become command packs with a free-text `execute_arguments` field; the argument syntax is not
  modelled beyond that.
- `Time` resources use `DateTime` (millisecond timestamp) while LwM2M transmits seconds.
- **No varint attributes (`[A]` / `[V]` / `[X]`) are emitted anywhere.** They must state where a number's values
  actually sit, and LwM2M says nothing about the distribution of a resource — only its range, which `[MinMax]`
  already expresses. Guessing would make the wire larger. Add them by hand where you know the traffic.
- The `Duration` aliases are inferred from resource names and units, so the mapping is a good default rather than a
  certainty: check resources whose name merely contains `Period` or `Interval`, and any duration you want at a
  coarser precision than one step of its unit.
- Every `Float` is a `double`; the registry does not say which resources would fit `float`.
- `RangeEnumeration` is only enforced for integer `a..b` ranges (as `[MinMax]`); everything else is carried as text.
- Multi-instance Opaque resources need the `Blob` sub-pack wrapper because AdHoc does not allow a typedef of a
  collection as a list element.
- One registry resource has an empty `Type` without being executable; it is emitted as bytes.
