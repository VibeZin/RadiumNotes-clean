//! Artefact patch: embedded faces for text WinAnsiEncoding cannot show.
//!
//! The standard 14 fonts only cover Western European text, and the upstream crate sent
//! everything else to a non-embedded Adobe CID font, which viewers without that font cannot
//! draw. Here Cyrillic, Greek, Latin Extended and similar scripts are set in Source Sans 3
//! (bundled in `engine/assets/fonts`, SIL OFL), embedded as a subset of the glyphs used.
//! Scripts Source Sans lacks (CJK, Arabic, Indic...) still use the old fallback.

use std::collections::BTreeMap;
use std::fmt::Write as _;

use skrifa::instance::{LocationRef, Size};
use skrifa::{FontRef, MetadataProvider};
use subsetter::GlyphRemapper;

/// The three embedded faces. Bold italic uses `Bold`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Face {
    Regular,
    Bold,
    Italic,
}

pub(crate) const FACES: [Face; 3] = [Face::Regular, Face::Bold, Face::Italic];

static REGULAR: &[u8] = include_bytes!("../../../assets/fonts/SourceSans3-Regular.ttf");
static BOLD: &[u8] = include_bytes!("../../../assets/fonts/SourceSans3-Bold.ttf");
static ITALIC: &[u8] = include_bytes!("../../../assets/fonts/SourceSans3-It.ttf");

/// Glyph ids used per face, with one character each (for the ToUnicode map).
pub(crate) type Used = [BTreeMap<u16, char>; 3];

impl Face {
    pub(crate) fn index(self) -> usize {
        match self {
            Face::Regular => 0,
            Face::Bold => 1,
            Face::Italic => 2,
        }
    }

    fn data(self) -> &'static [u8] {
        match self {
            Face::Regular => REGULAR,
            Face::Bold => BOLD,
            Face::Italic => ITALIC,
        }
    }

    fn base_name(self) -> &'static str {
        match self {
            Face::Regular => "SourceSans3-Regular",
            Face::Bold => "SourceSans3-Bold",
            Face::Italic => "SourceSans3-It",
        }
    }

    fn font(self) -> Option<FontRef<'static>> {
        FontRef::new(self.data()).ok()
    }

    /// The glyph for `c` and its advance in 1/1000 em, if the face has it.
    pub(crate) fn glyph(self, c: char) -> Option<(u16, u16)> {
        let font = self.font()?;
        let gid = font.charmap().map(c)?;
        let id = u16::try_from(gid.to_u32()).ok().filter(|g| *g != 0)?;
        let upem = font.metrics(Size::unscaled(), LocationRef::default()).units_per_em as f32;
        let adv = font.glyph_metrics(Size::unscaled(), LocationRef::default()).advance_width(gid)?;
        Some((id, (adv * 1000.0 / upem.max(1.0)).round().clamp(0.0, 65535.0) as u16))
    }
}

/// True when the regular face has a glyph for `c`.
pub(crate) fn covers(c: char) -> bool {
    Face::Regular.glyph(c).is_some()
}

/// A PDF hex string of the glyph ids of `text` (two bytes each, `Identity-H`); the glyphs are
/// noted in `used`.
pub(crate) fn hex_string(face: Face, text: &str, used: &mut BTreeMap<u16, char>) -> String {
    let mut s = String::with_capacity(text.len() * 4 + 2);
    s.push('<');
    for c in text.chars() {
        let gid = face.glyph(c).map_or(0, |(g, _)| g);
        if gid != 0 {
            used.entry(gid).or_insert(c);
        }
        let _ = write!(s, "{gid:04X}");
    }
    s.push('>');
    s
}

/// The objects for one embedded face: a Type0 font at `id`, then its descendant, descriptor,
/// font file, CID-to-GID map and ToUnicode map (`id` .. `id + 5`). `None` if nothing is used
/// or the subset fails.
pub(crate) fn objects(face: Face, id: u32, used: &BTreeMap<u16, char>) -> Option<Vec<(u32, Vec<u8>)>> {
    if used.is_empty() {
        return None;
    }
    let font = face.font()?;
    let mut remapper = GlyphRemapper::new();
    for gid in used.keys() {
        remapper.remap(*gid);
    }
    let subset = subsetter::subset(face.data(), 0, &remapper).ok()?;
    let metrics = font.metrics(Size::unscaled(), LocationRef::default());
    let k = 1000.0 / (metrics.units_per_em as f32).max(1.0);
    let (ascent, descent) = (metrics.ascent * k, metrics.descent * k);
    let bbox = metrics.bounds.map_or([-200.0, descent, 1200.0, ascent], |b| [b.x_min * k, b.y_min * k, b.x_max * k, b.y_max * k]);
    let tag = format!("{}+{}", tag_for(used), face.base_name());
    let (cid, desc, file, map, uni) = (id + 1, id + 2, id + 3, id + 4, id + 5);

    let mut widths = String::new();
    for (gid, c) in used {
        let w = face.glyph(*c).map_or(500, |(_, w)| w);
        let _ = write!(widths, "{gid} [{w}] ");
    }
    let italic = if face == Face::Italic { -12 } else { 0 };
    let stem = if face == Face::Bold { 140 } else { 80 };
    let mut out = vec![
        (id, format!("<< /Type /Font /Subtype /Type0 /BaseFont /{tag} /Encoding /Identity-H /DescendantFonts [{cid} 0 R] /ToUnicode {uni} 0 R >>").into_bytes()),
        (
            cid,
            format!(
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /{tag} /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor {desc} 0 R /CIDToGIDMap {map} 0 R /DW 500 /W [{widths}] >>"
            )
            .into_bytes(),
        ),
        (
            desc,
            format!(
                "<< /Type /FontDescriptor /FontName /{tag} /Flags 32 /FontBBox [{:.0} {:.0} {:.0} {:.0}] /ItalicAngle {italic} /Ascent {:.0} /Descent {:.0} /CapHeight {:.0} /StemV {stem} /FontFile2 {file} 0 R >>",
                bbox[0], bbox[1], bbox[2], bbox[3], ascent, descent, ascent * 0.7
            )
            .into_bytes(),
        ),
    ];
    let mut f = format!("<< /Length {} /Length1 {} >>\nstream\n", subset.len(), subset.len()).into_bytes();
    f.extend_from_slice(&subset);
    f.extend_from_slice(b"\nendstream");
    out.push((file, f));

    // CID (the original glyph id) -> glyph id in the subset.
    let top = used.keys().next_back().copied().unwrap_or(0) as usize;
    let mut map_bytes = vec![0u8; (top + 1) * 2];
    for gid in used.keys() {
        if let Some(new) = remapper.get(*gid) {
            let i = *gid as usize * 2;
            map_bytes[i..i + 2].copy_from_slice(&new.to_be_bytes());
        }
    }
    let mut m = format!("<< /Length {} >>\nstream\n", map_bytes.len()).into_bytes();
    m.extend_from_slice(&map_bytes);
    m.extend_from_slice(b"\nendstream");
    out.push((map, m));

    // ToUnicode, so text can be searched and copied.
    let mut cmap = String::from(
        "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n1 begincodespacerange <0000> <FFFF> endcodespacerange\n",
    );
    let entries: Vec<(&u16, &char)> = used.iter().collect();
    for chunk in entries.chunks(100) {
        let _ = writeln!(cmap, "{} beginbfchar", chunk.len());
        for (gid, c) in chunk {
            let mut buf = [0u16; 2];
            let hex: String = c.encode_utf16(&mut buf).iter().map(|u| format!("{u:04X}")).collect();
            let _ = writeln!(cmap, "<{gid:04X}> <{hex}>");
        }
        cmap.push_str("endbfchar\n");
    }
    cmap.push_str("endcmap CMapName currentdict /CMap defineresource pop end end");
    out.push((uni, format!("<< /Length {} >>\nstream\n{cmap}\nendstream", cmap.len()).into_bytes()));
    Some(out)
}

/// A six-letter subset tag derived from the glyphs used.
fn tag_for(used: &BTreeMap<u16, char>) -> String {
    let mut h: u32 = 2_166_136_261;
    for g in used.keys() {
        h = (h ^ u32::from(*g)).wrapping_mul(16_777_619);
    }
    (0..6)
        .map(|_| {
            let c = char::from(b'A' + (h % 26) as u8);
            h = (h / 26).wrapping_add(0x9E37_79B9 >> 4);
            c
        })
        .collect()
}
