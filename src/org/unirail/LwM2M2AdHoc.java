package org.unirail;

import org.unirail.adhoc.AdHocWriter;
import org.unirail.adhoc.Originals;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.I3;
import static org.unirail.adhoc.AdHocWriter.I4;
import static org.unirail.adhoc.AdHocWriter.doc;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.str;

/**
 * OMA LwM2M object definitions (XML, https://github.com/OpenMobileAlliance/lwm2m-registry) → AdHoc protocol description.
 *
 * <p>Usage: <code>java -cp out org.unirail.LwM2M2AdHoc &lt;object.xml | folder&gt; [output folder]</code>
 * (output defaults to <code>&lt;cwd&gt;/AdHoc</code>). A folder is one registry and becomes ONE descriptor
 * <code>LwM2M.cs</code> bundling every <code>&lt;Object ObjectType="MODefinition"&gt;</code> found in it; a single
 * file becomes <code>&lt;ObjectName&gt;.cs</code>.
 *
 * <p>Mapping: object → transmittable pack (Dashboard id = ObjectID) carrying its metadata as constants; readable /
 * writable resource → field with the LwM2M type translated, resource metadata as custom attributes; executable
 * resource → nested empty command pack <code>&lt;Object&gt;.&lt;Resource&gt;_Execute</code> sent Server → Client.
 */
public class LwM2M2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.LwM2M2AdHoc <object.xml | folder with LwM2M object XMLs> [output folder]");
			System.out.println("       output folder defaults to <current dir>/AdHoc");
			return;
		}
		Path src = Paths.get(args[0]);
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		Files.createDirectories(dst);

		List<File> files = new ArrayList<>();
		if (Files.isDirectory(src)) {
			File[] xml = src.toFile().listFiles((d, n) -> n.endsWith(".xml"));
			if (xml != null) files.addAll(Arrays.asList(xml));
		} else files.add(src.toFile());
		if (files.isEmpty()) {
			System.err.println("No .xml files found in `" + src.toAbsolutePath() + "`.");
			System.exit(1);
			return;
		}
		// Registry files are named by object id: sort numerically, everything else after.
		files.sort(Comparator.comparingLong((File f) -> {
			String n = f.getName().replace(".xml", "");
			return n.matches("\\d+") ? Long.parseLong(n) : Long.MAX_VALUE;
		}).thenComparing(File::getName));

		List<LwObject> objects = new ArrayList<>();
		int failed = 0, skipped = 0;
		for (File f : files)
			try {
				LwObject o = LwObject.load(f);
				if (o == null) { skipped++; continue; }
				objects.add(o);
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + f + ": " + e);
				e.printStackTrace();
			}
		if (objects.isEmpty()) {
			System.err.println("No <Object ObjectType=\"MODefinition\"> found.");
			System.exit(2);
			return;
		}
		// A folder is the registry → LwM2M.cs; a single object → LwM2M_<Object>.cs (the pack itself keeps the object's name).
		String project = Files.isDirectory(src) ? "LwM2M" : "LwM2M_" + ident(objects.get(0).name);
		Path out = dst.resolve(project + ".cs");
		List<String> originals = Files.isDirectory(src) ? folderOriginal(src, files) : Originals.of(List.of(src));
		Files.write(out, new Emitter(project, objects, src.getFileName().toString(), originals).emit().getBytes(StandardCharsets.UTF_8));
		System.out.printf("%s -> %s  (%d objects, %d resources, %d executable; %d files skipped, %d failed)%n",
				src, out, objects.size(), objects.stream().mapToInt(o -> o.resources.size()).sum(),
				objects.stream().mapToInt(o -> (int) o.resources.stream().filter(Resource::executable).count()).sum(), skipped, failed);
		if (0 < failed) System.exit(2);
	}

	/**
	 * The page of the original of a folder of object files. {@code fetch-samples.sh} copies the files of the registry
	 * straight into samples/ and lists each of them, so the folder has no line of its own: when all its files come
	 * from one folder of the original, that folder is linked instead of hundreds of files.
	 */
	static List<String> folderOriginal(Path dir, List<File> files) {
		String own = Originals.of(dir);
		if (own != null) return List.of(own);
		List<Path> inputs = new ArrayList<>();
		for (File f : files) inputs.add(f.toPath());
		List<String> pages = Originals.of(inputs);
		String folder = null;
		for (String page : pages) {
			String parent = page.substring(0, page.lastIndexOf('/') + 1);
			if (folder == null) folder = parent;
			else if (!folder.equals(parent)) return pages;
		}
		if (folder == null) return pages;
		return List.of(folder.startsWith("https://github.com/") ? folder.replaceFirst("/blob/", "/tree/") : folder); // GitHub shows a folder under tree/
	}

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	static final class Resource {
		int id;
		String name, ops = "", type = "", range = "", units = "", doc = "";
		boolean multiple, mandatory;

		boolean executable() { return ops.contains("E"); }
	}

	static final class LwObject {
		int id;
		String file, name, urn = "", lwm2mVersion = "", objectVersion = "", doc = "";
		boolean multiple, mandatory;
		final List<Resource> resources = new ArrayList<>();

		/** Returns null when the file holds no object definition (Common.xml, DDF.xml, LWM2M_senml_units.xml). */
		static LwObject load(File f) throws Exception {
			DocumentBuilderFactory df = DocumentBuilderFactory.newInstance();
			df.setNamespaceAware(false);
			df.setExpandEntityReferences(true);
			Document d = df.newDocumentBuilder().parse(f);
			Element root = d.getDocumentElement();
			Element obj = null;
			for (Element e : children(root, "Object")) if ("MODefinition".equals(e.getAttribute("ObjectType"))) { obj = e; break; }
			if (obj == null) return null;

			LwObject o = new LwObject();
			o.file = f.getName();
			o.name = text(obj, "Name");
			o.id = Integer.parseInt(text(obj, "ObjectID").trim());
			o.urn = text(obj, "ObjectURN");
			o.lwm2mVersion = text(obj, "LWM2MVersion");
			o.objectVersion = text(obj, "ObjectVersion");
			o.multiple = "Multiple".equals(text(obj, "MultipleInstances").trim());
			o.mandatory = "Mandatory".equals(text(obj, "Mandatory").trim());
			String d1 = text(obj, "Description1").trim(), d2 = text(obj, "Description2").trim();
			o.doc = d2.isEmpty() ? d1 : d1.isEmpty() ? d2 : d1 + "\n" + d2;

			for (Element res : children(obj, "Resources"))
				for (Element item : children(res, "Item")) {
					Resource r = new Resource();
					r.id = Integer.parseInt(item.getAttribute("ID").trim());
					r.name = text(item, "Name");
					r.ops = text(item, "Operations").trim();
					r.type = text(item, "Type").trim();
					r.range = text(item, "RangeEnumeration").trim();
					r.units = text(item, "Units").trim();
					r.doc = text(item, "Description");
					r.multiple = "Multiple".equals(text(item, "MultipleInstances").trim());
					r.mandatory = "Mandatory".equals(text(item, "Mandatory").trim());
					o.resources.add(r);
				}
			return o;
		}
	}

	static List<Element> children(Element e, String tag) {
		List<Element> list = new ArrayList<>();
		NodeList nl = e.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node n = nl.item(i);
			if (n instanceof Element && ((Element) n).getTagName().equals(tag)) list.add((Element) n);
		}
		return list;
	}

	static String text(Element e, String tag) {
		List<Element> c = children(e, tag);
		return c.isEmpty() ? "" : c.get(0).getTextContent();
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		final String project, source;
		final List<String> originals; // the pages of what the description is made from, for its header
		final List<LwObject> objects;
		final StringBuilder sb = new StringBuilder(1 << 20);
		final Set<String> packNames = new HashSet<>();
		final Map<LwObject, String> packName = new LinkedHashMap<>();
		final List<String> objectPacks = new ArrayList<>(), executePacks = new ArrayList<>();

		Emitter(String project, List<LwObject> objects, String source, List<String> originals) {
			this.project = project;
			this.objects = objects;
			this.source = source;
			this.originals = originals;
			packNames.add("ObjectLink");
			packNames.add("Blob");
			packNames.add(project);
			// Reserve every alias name the emitter may introduce, so no object pack can collide with one.
			for (String u : new String[]{"Seconds", "Milliseconds", "Minutes", "Hours"}) packNames.add("DurationIn" + u);
			for (LwObject o : objects) {
				String n = ident(o.name);
				if (packNames.contains(n)) n = ident(o.name + "_" + o.id);
				n = AdHocWriter.unique(n, packNames);
				packName.put(o, n);
			}
		}

		String emit() {
			AdHocWriter.fileHeader(sb, "LwM2M2AdHoc", objects.size() + " LwM2M object definitions from " + source, originals,
					"OMA LwM2M registry: https://github.com/OpenMobileAlliance/lwm2m-registry",
					"Objects are packs; executable resources are nested *_Execute command packs.",
					"LwM2M ObjectIDs / resource ids address the SOURCE's tree and are kept as constants and attributes;",
					"the AdHoc pack ids in the Dashboard are assigned by AdHocAgent.");
			sb.append("namespace org.lwm2m {\n");

			// Body first: it registers the pack names the Dashboard lists.
			StringBuilder body = new StringBuilder(1 << 20);
			Map<String, Integer> ids = new LinkedHashMap<>();
			body.append(I1).append("public interface ").append(project).append(" {\n\n");
			// LwM2M puts no ceiling on a multiple-instance resource or an Opaque value, and AdHoc's 255 default is
			// far too small for either. Raise the global defaults; a resource whose RangeEnumeration states a
			// length still gets its own [D(+N)] / [D(N)].
			body.append(I2).append("/** Permissive defaults for LwM2M-sourced data: the registry states no ceiling for instances or blobs. */\n");
			body.append(I2).append("enum _DefaultMaxLengthOf {\n");
			body.append(I3).append("Arrays  = 65_535,\n");
			body.append(I3).append("Maps    = 65_535,\n");
			body.append(I3).append("Sets    = 65_535,\n");
			body.append(I3).append("Strings = 65_535,\n");
			body.append(I2).append("}\n\n");
			body.append(I2).append("/**\n");
			body.append(I2).append("LwM2M Objlnk value: a reference to an object instance, 16-bit object id and 16-bit instance id.\n");
			body.append(I2).append("Used only as a field type, so it stays a non-transmittable value pack.\n");
			body.append(I2).append("*/\n");
			body.append(I2).append("class ObjectLink {\n");
			body.append(I3).append("ushort object_id;\n");
			body.append(I3).append("ushort instance_id;\n");
			body.append(I2).append("}\n");
			for (LwObject o : objects) object(body, o, ids);
			topology(body);
			attributes(body);
			body.append(I1).append("}\n");

			AdHocWriter.dashboard(sb, I1, ids);
			sb.append(body);
			sb.append("}\n");
			return sb.toString();
		}

		void object(StringBuilder b, LwObject o, Map<String, Integer> ids) {
			String cls = packName.get(o);
			// A pack id is AdHoc's own internal matter: the agent assigns and maintains it. The LwM2M ObjectID
			// describes the SOURCE's addressing (the `/3/0/1` path), so it stays a constant inside the pack.
			ids.put(cls, null);
			b.append('\n').append(I2).append("// ═════════════════════════ ").append(o.file).append(": ").append(o.name.trim()).append(" (").append(o.urn.trim()).append(") ═════════════════════════\n");
			doc(b, I2, o.doc);
			b.append(I2).append("class ").append(cls).append(" {\n");
			b.append(I3).append("public const ushort lwm2m_object_id          = ").append(o.id).append(";\n");
			b.append(I3).append("public const string lwm2m_urn                = ").append(str(o.urn.trim())).append(";\n");
			b.append(I3).append("public const string lwm2m_version            = ").append(str(o.lwm2mVersion.trim())).append(";\n");
			b.append(I3).append("public const string lwm2m_object_version     = ").append(str(o.objectVersion.trim())).append(";\n");
			b.append(I3).append("public const bool   lwm2m_multiple_instances = ").append(o.multiple).append(";\n");
			b.append(I3).append("public const bool   lwm2m_mandatory          = ").append(o.mandatory).append(";\n");

			Set<String> taken = new HashSet<>(Arrays.asList(cls, "lwm2m_object_id", "lwm2m_urn", "lwm2m_version", "lwm2m_object_version",
					"lwm2m_multiple_instances", "lwm2m_mandatory", "ObjectLink", "Blob"));
			List<Resource> executables = new ArrayList<>();
			for (Resource r : o.resources) {
				if (r.executable()) { executables.add(r); continue; }
				b.append('\n');
				doc(b, I3, r.doc);
				b.append(I3).append(field(r, memberName(r, taken))).append('\n');
			}
			for (Resource r : executables) {
				String n = memberName(r, taken, "_Execute");
				executePacks.add(cls + "." + n);
				ids.put(cls + "." + n, null);
				b.append('\n');
				doc(b, I3, (r.doc.trim().isEmpty() ? "" : r.doc.trim() + "\n") + "Execute operation on resource " + r.id + " (" + r.name.trim() + "); sent by the Server to the Client."
						+ (r.type.isEmpty() ? "" : " Argument type: " + r.type + "."));
				b.append(I3).append(resourceAttributes(r)).append("public class ").append(n).append(" {\n");
				b.append(I4).append("/** Optional LwM2M Execute arguments, in the `<id>='<value>'` text form of the specification. */\n");
				b.append(I4).append("[D(+1024)] string? execute_arguments;\n");
				b.append(I3).append("}\n");
			}
			b.append(I2).append("}\n");
			objectPacks.add(cls);
		}

		static String memberName(Resource r, Set<String> taken) { return memberName(r, taken, ""); }

		/** ident(name)+suffix; on a collision with the class / a sibling the resource id is appended. */
		static String memberName(Resource r, Set<String> taken, String suffix) {
			String n = ident(r.name + suffix);
			if (taken.contains(n)) n = ident(r.name + "_" + r.id + suffix);
			return AdHocWriter.unique(n, taken);
		}

		static final Pattern INT_RANGE = Pattern.compile("^\\s*(-?(?:0[xX][0-9a-fA-F]+|\\d+))\\s*\\.\\.\\s*(-?(?:0[xX][0-9a-fA-F]+|\\d+))\\s*$");
		static final Pattern BYTES = Pattern.compile("^\\s*(?:(\\d+)\\s*\\.\\.\\s*)?(\\d+)\\s*[bB]ytes?\\s*$");
		static final Pattern INT_LIST = Pattern.compile("^\\s*\\d+(\\s*,\\s*\\d+)*\\s*$");

		/**
		 * For String / Opaque / Corelnk resources the RangeEnumeration describes the LENGTH: `0..255`, `32 bytes`,
		 * `1..64 bytes`, `16,32,48` (allowed lengths) or a single number. Returns the maximum length or -1.
		 */
		static long lengthLimit(String range) {
			Matcher by = BYTES.matcher(range), ir = INT_RANGE.matcher(range);
			if (by.matches()) return Long.parseLong(by.group(2));
			if (ir.matches()) {
				long[] l = parseRange(ir);
				return l == null ? -1 : l[1];
			}
			if (INT_LIST.matcher(range).matches()) {
				long max = -1;
				for (String s : range.split(",")) max = Math.max(max, Long.parseLong(s.trim()));
				return max;
			}
			return -1;
		}

		int physicsHints;

		static final Pattern P_FLOOR = Pattern.compile("(?i)\\b(counters?|counts?|errors?|retries|retry|attempts?|" +
				"failures?|reboots?|restarts?|sequence|total|cumulative)\\b");
		static final Pattern P_CEILING = Pattern.compile("(?i)\\b(remaining|left|available|headroom)\\b");
		static final Pattern P_CENTRED = Pattern.compile("(?i)\\b(rssi|rsrp|rsrq|snr|sinr|signal\\s*strength|" +
				"delta|offset|deviation|drift|correction|bias|tilt|roll|pitch|yaw|latitude|longitude)\\b");

		/**
		 * Where a number's values actually sit is the one thing AdHoc can express and the object XML never states.
		 * The registry does hint at it through resource NAMES and UNITS, but choosing a varint is a decision taken
		 * after looking at real traffic, so the converter names the candidate in a comment on the field instead of
		 * inventing an attribute - and never drops the question silently.
		 *
		 * @return the trailing comment, or "" when the registry gives no hint
		 */
		String physicsHint(Resource r) {
			String n = r.name, u = r.units.trim();
			String hint;
			if (P_CENTRED.matcher(n).find() || u.equalsIgnoreCase("Cel") || u.equalsIgnoreCase("dBm") || u.equalsIgnoreCase("dB"))
				hint = "centred, excursions both ways -> consider [X(amplitude)]";
			else if (P_CEILING.matcher(n).find())
				hint = "hugs its ceiling, rare excursions down -> consider [V(max)]";
			else if (P_FLOOR.matcher(n).find())
				hint = "floor at 0, unbounded above -> consider [A] while the typical value stays under ~2_000_000";
			else return "";
			physicsHints++;
			return "  // physics: " + r.name.trim().toLowerCase() + (u.isEmpty() ? "" : ", " + u) + ", " + hint;
		}

		String field(Resource r, String name) {
			List<String> attrs = new ArrayList<>();
			String type;
			String comment = "";
			boolean nullable = !r.mandatory; // for value types; reference types are optional anyway
			String range = r.range;
			Matcher ir = INT_RANGE.matcher(range), by = BYTES.matcher(range);
			boolean isInt = r.type.equals("Integer") || r.type.equals("Unsigned Integer");
			long[] lim = isInt && ir.matches() ? parseRange(ir) : null;
			if (lim != null && lim[1] <= lim[0]) lim = null; // a single value or an inverted range: AdHoc rejects [MinMax] with min >= max
			if (isInt && lim == null && !range.isEmpty() && !by.matches()) attrs.add("Range(" + str(range) + ")");
			long len = lengthLimit(range);

			switch (r.type) {
				case "Integer":
				case "Unsigned Integer": {
					String duration = durationAlias(r);
					if (duration != null) {
						// An elapsed time / timeout / period: AdHoc models this natively, so a Duration alias
						// replaces the bare integer. The alias carries the range and the precision.
						type = duration;
						attrs.removeIf(a -> a.startsWith("Range("));
						break;
					}
					if (lim == null) {
						// No hard range: the value is unbounded, so its physics is the only thing that could
						// shrink it. The registry does not state that, but the name and units often hint at it.
						type = r.type.equals("Integer") ? "long" : "ulong";
						comment = physicsHint(r);
					} else {
						long min = lim[0], max = lim[1];
						if (r.type.equals("Unsigned Integer") && min < 0) min = 0;
						type = intType(min, max);
						if (!fullSpan(type, min, max)) attrs.add(0, "MinMax(" + min + ", " + max + ")");
					}
					break;
				}
				case "Float":
					type = "double";
					if (!range.isEmpty()) attrs.add("Range(" + str(range) + ")");
					break;
				case "Boolean":
					type = "bool";
					if (!range.isEmpty()) attrs.add("Range(" + str(range) + ")");
					break;
				case "String":
					type = "string";
					nullable = false;
					if (0 < len) attrs.add(0, "D(+" + len + ")");
					else if (!range.isEmpty()) attrs.add("Range(" + str(range) + ")");
					break;
				case "Corelnk":
					type = "string";
					nullable = false;
					attrs.add(0, "D(+" + (0 < len ? len : 4096) + ")");
					if (len <= 0 && !range.isEmpty()) attrs.add("Range(" + str(range) + ")");
					break;
				case "Opaque":
				case "":
					// An empty Type on a non-executable resource occurs in a few registry files; treat it as raw bytes.
					type = "Binary[,,]";
					nullable = false;
					attrs.add(0, "D(" + (0 < len ? len : 65535) + ")");
					if (len <= 0 && !range.isEmpty()) attrs.add("Range(" + str(range) + ")");
					break;
				case "Time":
					type = "DateTime"; // Unix time in seconds in LwM2M; AdHoc transmits it as a 64-bit millisecond timestamp
					if (!range.isEmpty()) attrs.add("Range(" + str(range) + ")");
					break;
				case "Objlnk":
					type = "ObjectLink";
					if (!range.isEmpty()) attrs.add("Range(" + str(range) + ")");
					break;
				default:
					System.err.println("WARNING resource " + r.id + " `" + r.name + "`: unknown Type `" + r.type + "`, kept as bytes");
					type = "Binary[,,]";
					nullable = false;
					attrs.add(0, "D(65535)");
			}

			if (r.multiple) {
				// A multiple-instance resource is a list of values; a list of byte blobs is a list of Blob sub-packs.
				if (type.equals("Binary[,,]")) {
					type = "Blob[,,]";
					attrs.removeIf(a -> a.startsWith("D("));
					usesBlob = true;
				} else if (type.equals("string")) type = "string[,,]";
				else type = type + "[,,]";
				nullable = false;
			} else if (nullable) type += "?";

			String meta = resourceAttributes(r);
			String all = meta.isEmpty() && attrs.isEmpty() ? "" : "[" + String.join(", ", attrs) + (attrs.isEmpty() ? "" : ", ") + meta.substring(1, meta.length() - 2) + "] ";
			return all + type + " " + name + ";" + comment;
		}

		boolean usesBlob;
		/** Duration aliases actually used, alias name → (max steps, precision expression, comment). */
		final Map<String, String[]> durations = new LinkedHashMap<>();

		static final Pattern DURATION_NAME = Pattern.compile(
				"(?i)\\b(lifetime|timeout|(minimum|maximum|min|max)\\s+period|period|duration|interval|uptime|" +
				"up\\s*time|elapsed|age|delay|backoff|hold\\s*time|(on|off)\\s*time|runtime|run\\s*time|" +
				"time\\s*(out|to\\s*live)|ttl|expir\\w*)\\b");

		/**
		 * An LwM2M resource that measures how long something takes / lasts is an elapsed duration, which AdHoc
		 * models natively with a {@code Duration} alias. Recognised only when the name says so AND the resource is
		 * a time-unit integer (units s / ms / min / h, or no units on a plainly-named duration) - never guessed
		 * from the units alone, because {@code s} also appears on wall-clock resources.
		 *
		 * @return the alias class name, registered for emission, or null when the resource is not a duration
		 */
		String durationAlias(Resource r) {
			if (!DURATION_NAME.matcher(r.name).find()) return null;
			String u = r.units.trim().toLowerCase();
			String unit, precision, comment;
			switch (u) {
				case "s": case "sec": case "seconds": unit = "Seconds"; precision = "TimeSpan.FromSeconds(1)"; comment = "1 s"; break;
				case "ms": case "milliseconds": unit = "Milliseconds"; precision = "TimeSpan.FromMilliseconds(1)"; comment = "1 ms"; break;
				case "min": case "minutes": unit = "Minutes"; precision = "TimeSpan.FromMinutes(1)"; comment = "1 min"; break;
				case "h": case "hours": unit = "Hours"; precision = "TimeSpan.FromHours(1)"; comment = "1 h"; break;
				case "": unit = "Seconds"; precision = "TimeSpan.FromSeconds(1)"; comment = "1 s (the registry states no unit; LwM2M durations are seconds)"; break;
				default: return null; // a "period" counted in something other than time is not a Duration
			}
			// One shared alias per precision: max is left at the AdHoc default so any registry range fits.
			String name = "DurationIn" + unit;
			durations.putIfAbsent(name, new String[]{precision, comment});
			return name;
		}

		/** `[ResourceId(n), Operations("RW"), Units("Cel"), Mandatory] ` - the LwM2M resource metadata attributes. */
		static String resourceAttributes(Resource r) {
			List<String> a = new ArrayList<>();
			a.add("ResourceId(" + r.id + ")");
			if (!r.ops.isEmpty()) a.add("Operations(" + str(r.ops) + ")");
			if (!r.units.isEmpty()) a.add("Units(" + str(r.units) + ")");
			if (r.mandatory) a.add("Mandatory");
			return "[" + String.join(", ", a) + "] ";
		}

		static long[] parseRange(Matcher m) {
			try {
				return new long[]{decode(m.group(1)), decode(m.group(2))};
			} catch (NumberFormatException e) { return null; } // beyond long, e.g. 0..18446744073709551615
		}

		static long decode(String s) {
			s = s.trim();
			boolean neg = s.startsWith("-");
			if (neg) s = s.substring(1);
			long v = s.startsWith("0x") || s.startsWith("0X") ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
			return neg ? -v : v;
		}

		static String intType(long min, long max) {
			if (0 <= min) {
				if (max <= 255) return "byte";
				if (max <= 65535) return "ushort";
				if (max <= 4294967295L) return "uint";
				return "ulong";
			}
			if (-128 <= min && max <= 127) return "sbyte";
			if (-32768 <= min && max <= 32767) return "short";
			if (Integer.MIN_VALUE <= min && max <= Integer.MAX_VALUE) return "int";
			return "long";
		}

		static boolean fullSpan(String type, long min, long max) {
			switch (type) {
				case "byte": return min == 0 && max == 255;
				case "ushort": return min == 0 && max == 65535;
				case "uint": return min == 0 && max == 4294967295L;
				case "sbyte": return min == -128 && max == 127;
				case "short": return min == -32768 && max == 32767;
				case "int": return min == Integer.MIN_VALUE && max == Integer.MAX_VALUE;
				default: return true; // long / ulong: MinMax adds nothing the type does not already say
			}
		}

		void topology(StringBuilder b) {
			if (usesBlob) {
				b.append('\n').append(I2).append("/** One instance of a multiple-instance Opaque resource: the bytes of that instance. */\n");
				b.append(I2).append("class Blob {\n");
				b.append(I3).append("[D(65535)] Binary[,,] bytes;\n");
				b.append(I2).append("}\n");
			}
			if (!durations.isEmpty()) {
				b.append('\n').append(I2).append("// ═════════════════════════ elapsed-time aliases ═════════════════════════\n\n");
				b.append(I2).append("// LwM2M writes lifetimes, periods and timeouts as plain integers. AdHoc models an elapsed\n");
				b.append(I2).append("// duration natively: the alias fixes the step size, and the generator picks the narrowest\n");
				b.append(I2).append("// container that holds the range (expanding max to fill the allocated bytes).\n");
				for (Map.Entry<String, String[]> d : durations.entrySet()) {
					b.append('\n').append(I2).append("/** Elapsed duration, one step = ").append(d.getValue()[1]).append(". */\n");
					b.append(I2).append("class ").append(d.getKey()).append(" : Duration {\n");
					b.append(I3).append("public TimeSpan precision => ").append(d.getValue()[0]).append(";\n");
					b.append(I2).append("}\n");
				}
			}
			b.append('\n').append(I2).append("// ═════════════════════════ topology ═════════════════════════\n\n");
			b.append(I2).append("// LwM2M has exactly two roles: the Server manages the Client (the device). Object instances travel in\n");
			b.append(I2).append("// both directions (Read / Write / Observe / Notify); Execute is always Server -> Client.\n\n");
			AdHocWriter.host(b, I2, "Server", "LwM2M Server (management side, Left)");
			AdHocWriter.host(b, I2, "Client", "LwM2M Client (the device, Right)");
			b.append(I2).append("interface Management : Connects<Server, Client> {\n");
			b.append(I3).append("// Object instances: either side may send any object (Read/Write/Observe/Notify payloads).\n");
			branch(b, "_____lr_____", "Objects", objectPacks);
			if (!executePacks.isEmpty()) {
				b.append('\n').append(I3).append("// Execute operations: the Server invokes an executable resource on the Client.\n");
				branch(b, "l____________", "Execute", executePacks);
			}
			b.append(I2).append("}\n");
		}

		static void branch(StringBuilder b, String attr, String state, List<String> packs) {
			if (packs.size() == 1) b.append(I3).append("[").append(attr).append("<").append(packs.get(0)).append(">]\n"); // a one-element tuple is not C#
			else {
				b.append(I3).append("[").append(attr).append("<(\n");
				for (int i = 0; i < packs.size(); i++)
					b.append(I4).append(packs.get(i)).append(i + 1 < packs.size() ? "," : "").append('\n');
				b.append(I3).append(")>]\n");
			}
			b.append(I3).append("struct ").append(state).append(" { }\n");
		}

		void attributes(StringBuilder b) {
			b.append('\n').append(I2).append("// ═════════════════════════ LwM2M resource metadata attributes ═════════════════════════\n\n");
			b.append(I2).append("// AdHoc custom attributes: the generator materialises them as constants next to the field, so the\n");
			b.append(I2).append("// resource id, allowed operations, units and value range stay available at runtime.\n\n");
			AdHocWriter.attribute(b, I2, "ResourceId", "LwM2M resource id (the `/object/instance/<resource>` path segment).", "int id");
			AdHocWriter.attribute(b, I2, "Operations", "Allowed operations: R, W, RW or E.", "string operations");
			AdHocWriter.attribute(b, I2, "Units", "SenML unit of the value, as written in the registry.", "string units");
			AdHocWriter.attribute(b, I2, "Range", "RangeEnumeration text that is not a plain integer range (float ranges, value lists, prose).", "string range");
			AdHocWriter.attribute(b, I2, "Mandatory", "The resource is mandatory in every instance of the object.");
		}
	}
}
