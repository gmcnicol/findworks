---
name: FindWorks
description: A calm operations ledger for knowledge-seeking work.
colors:
  action-cobalt: "#2851c5"
  action-cobalt-deep: "#1f43aa"
  navigation-navy: "#111a2b"
  navigation-active: "#263a59"
  cool-paper: "#f2f5f9"
  surface-white: "#ffffff"
  primary-ink: "#172033"
  secondary-ink: "#68778e"
  divider: "#d8e0ec"
  attention-ink: "#984018"
  attention-surface: "#fff0e8"
typography:
  display:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "clamp(2rem, 4vw, 3.2rem)"
    fontWeight: 760
    lineHeight: 1.05
    letterSpacing: "-0.035em"
  headline:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "1.28rem"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.015em"
  body:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
    fontSize: "0.74rem"
    fontWeight: 720
    lineHeight: 1.2
    letterSpacing: "0.035em"
rounded:
  sm: "9px"
  md: "12px"
  lg: "14px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "18px"
  lg: "28px"
  xl: "48px"
components:
  button-primary:
    backgroundColor: "{colors.action-cobalt}"
    textColor: "{colors.surface-white}"
    rounded: "{rounded.sm}"
    padding: "0 18px"
    height: "44px"
  button-secondary:
    backgroundColor: "{colors.surface-white}"
    textColor: "{colors.action-cobalt}"
    rounded: "{rounded.sm}"
    padding: "0 18px"
    height: "44px"
  navigation-active:
    backgroundColor: "{colors.navigation-active}"
    textColor: "{colors.surface-white}"
    rounded: "10px"
    padding: "0 12px"
    height: "42px"
  status-attention:
    backgroundColor: "{colors.attention-surface}"
    textColor: "{colors.attention-ink}"
    rounded: "{rounded.pill}"
    padding: "0 9px"
    height: "27px"
---

# Design System: FindWorks

## Overview

**Creative North Star: "The Operations Ledger"**

FindWorks should feel like a well-kept operational record: calm enough for sustained work, structured enough that state and next action are never ambiguous. The Investigator workspace uses compact information density, strong typographic order, and restrained color rather than decorative dashboards or oversized metrics.

The dark navigation frame establishes place; cool paper and white working surfaces keep the content legible under ordinary office lighting. Cobalt means navigation or action. Warm color is reserved for work that genuinely needs attention.

**Key Characteristics:**
- Persistent orientation across Investigator surfaces
- Dense but readable ledgers rather than galleries of equal cards
- Fine rules and tonal layers for structure
- Compact, text-first lifecycle states
- One clear next action per row

## Colors

The palette is restrained: deep navy framing, cool neutral work surfaces, one cobalt action color, and semantic color used only where state requires it.

### Primary
- **Action Cobalt:** Links, primary buttons, and interactive emphasis.
- **Deep Action Cobalt:** Hover state for primary actions.

### Secondary
- **Navigation Navy:** Persistent Investigator navigation and strong framing.
- **Navigation Active:** The selected destination within the dark frame.

### Neutral
- **Cool Paper:** Investigator workspace background.
- **Surface White:** Ledgers, forms, and focused work panels.
- **Primary Ink:** Headings, Mission titles, and high-value content.
- **Secondary Ink:** Supporting explanations and metadata.
- **Divider:** Table rules, section boundaries, and low-emphasis structure.

### Named Rules

**The Scarce Signal Rule.** Cobalt identifies interaction; warm hues identify attention. Do not scatter either color decoratively.

**The Paper and Frame Rule.** Investigator work sits on cool paper within a deep navigation frame. Interviewee surfaces may remain focused white panels without the administrative frame.

## Typography

**Display Font:** Native system sans-serif stack
**Body Font:** Native system sans-serif stack
**Interview Question Font:** Iowan Old Style, Palatino Linotype, Georgia, serif

**Character:** Investigator surfaces are direct and operational. The Interviewee’s active question uses a restrained editorial serif to slow the reading pace and separate the human prompt from interface controls.

### Hierarchy
- **Display** (760, responsive up to 3.2rem, 1.05): Workspace and major detail-page titles.
- **Headline** (700, 1.28rem, 1.2): Dashboard and page sections.
- **Title** (720, approximately 1rem): Discovery and Mission names in rows.
- **Body** (400, 1rem, 1.5): Explanations and form content, kept to roughly 68 characters where prose runs long.
- **Label** (720, 0.74rem, tracked uppercase): Table headings and compact field labels only.

### Named Rules

**The Name Carries Weight Rule.** Discovery, Mission, and Investigation Item names receive the strongest weight in operational rows; versions and metadata remain subordinate.

## Layout

The authenticated Investigator workspace uses a 236px persistent navigation column and a fluid content region capped at 1260px. Primary workspace content uses 52px desktop insets and a vertical section rhythm around 48–52px. Detail and review work narrows to approximately 1000px.

The Interviewee surface uses a warm paper background, an 820px shell, and a 700px reading column. The active question precedes every control, accepted-answer history stays collapsed, and alternative responses sit below the primary answer.

At 900px, navigation becomes a sticky header. At 640px, all destinations wrap into a visible compact menu, summaries form a two-column grid, and table rows become labelled vertical records. No primary destination may depend on hidden horizontal scrolling.

**The Parent Context Rule.** A Mission row always names its Discovery. Detail pages retain both global navigation and a local breadcrumb.

## Elevation & Depth

Depth is quiet and functional. The navigation frame separates by tone. Ledgers use a fine border plus a low ambient shadow; ordinary content sections remain flat. Focused panels may use the existing broader ambient shadow, but elevation is not a substitute for hierarchy.

### Shadow Vocabulary
- **Ledger Lift** (`0 12px 34px rgba(28, 47, 78, 0.055)`): Mission and findings ledgers only.
- **Focused Panel** (`0 20px 60px rgba(23, 32, 51, 0.08)`): Sign-in, Interviewee, and singular focused task panels.

**The Flat-by-Default Rule.** Use dividers and whitespace first. Add elevation only where a bounded working surface must separate from the page.

## Shapes

Working surfaces use gently rounded corners between 9px and 14px. Larger 20px panels are reserved for isolated focused tasks. Status labels and compact controls may use pill geometry; content containers do not. Borders are one pixel and cool neutral.

## Components

### Buttons
- **Shape:** Compact 9px corners with a 44px minimum target; pills are reserved for statuses.
- **Primary:** Action Cobalt with white text and medium-heavy weight.
- **Secondary:** White surface, cool border, and cobalt text.
- **Hover / Focus:** Darker action fill on hover; a visible three-pixel cool-blue focus ring with offset.

### Status Labels
- **Style:** Compact pills with semantic tonal backgrounds and dark text.
- **State:** Purple marks drafts, warm orange marks attention, green marks active work, blue-gray marks waiting, and blue marks reviewed completion.

### Ledgers
- **Corner Style:** Gently rounded outer frame (14px).
- **Background:** White body with a lightly tinted header.
- **Structure:** Fine horizontal rules, left-aligned content, right-aligned next action.
- **Responsive behavior:** Each table row becomes a labelled vertical record on narrow screens.

### Inputs / Fields
- **Style:** White field, one-pixel cool border, 12px corners, generous 12–14px inset.
- **Focus:** Use the shared visible focus ring; do not rely on border color alone.

### Navigation
- **Desktop:** Persistent dark column with a single lighter active row and account controls anchored at the bottom.
- **Compact:** Sticky dark header with every destination visible across wrapped rows; no icon-only destinations.

## Do's and Don'ts

### Do:
- **Do** show lifecycle state and the next action together.
- **Do** keep Discovery context visible anywhere Missions or findings are listed.
- **Do** use flat rows and rules when content needs comparison.
- **Do** preserve a focused, shell-free experience for external Interviewees.
- **Do** place each Mission review checkpoint action beside the content it confirms.
- **Do** keep Interviewee escape routes visible but visually secondary to the active answer.

### Don't:
- **Don't** create dead navigation destinations or placeholder dashboard modules.
- **Don't** use a grid of equal metric cards as the dashboard’s primary structure.
- **Don't** use warm attention color for ordinary decoration.
- **Don't** hide required mobile navigation behind horizontal scrolling.
