# Jarurat Mail / Campaign Studio - UI specification

The single contract every screen is built against. If a rule here and the code
disagree, the code is wrong.

Constraints that are not negotiable, because the box must render with zero
external requests and the app ships as one jar:

- No CSS framework, no icon font, no chart library, no CDN. Everything inlined
  or served from `/static`.
- Content-Security-Policy is `default-src 'self'`. No remote font, no remote
  image, no remote script.
- Thymeleaf templates plus vanilla JS. No build step, no bundler.
- Google Fonts cannot be loaded. The type stack is the system stack.

---

## 1. Two surfaces, one system

| Surface | Route | Who it is for |
|---|---|---|
| Mailbox | `/mail` | Everyone with a jarurat.care address. **The default on a phone.** |
| Campaign Studio | `/app` | Marketing and admin. Desktop-first. |

The mailbox is the product for most people. The console is the product for a
few. Phones get the mailbox; laptops get whichever the person signed in for.

---

## 2. Colour

Flat charcoal. Never navy, never purple, never a gradient as a surface. One
accent, used as an edge, a single primary button and a focus ring - never as a
large fill.

```
--bg              #141414   page ground
--surface         #1b1b1b   rail, topbar, sunken areas
--panel           #202020   cards, sheets, list rows
--panel-2         #252525   raised inside a panel
--panel-3         #2c2c2c   hover
--panel-4         #343434   pressed, avatar ground
--field           #262626   input rest
--field-focus     #2b2b2b   input focus

--border          rgba(255,255,255,.075)   hairline between rows
--border-strong   rgba(255,255,255,.14)    input outline, card edge
--border-soft     rgba(255,255,255,.045)   inside a panel

--text            #ededed
--text-dim        #b9b9b9
--text-mute       #949494
--text-faint      #7d7d7d

--primary         #2f6fed
--primary-hover   #3a7bf5
--primary-fg      #7aa8ff   accent text on dark
--success         #1f9d55   --success-fg #4cc38a
--warning         #d99e0b   --warning-fg #e3b341
--danger          #e0483c   --danger-fg  #f2776b
--accent          #19a7a0   second series colour in charts only
```

Contrast floor is 4.5:1 for body text and 3:1 for large text and UI edges,
measured against the surface it actually sits on, not against `--bg`.

`color-scheme: dark` is set on `:root`. Without it the user agent paints select
popups, date pickers, scrollbars and the autofill wash light blue on a near
black page, and no amount of token work reaches those.

There is no light theme and no `prefers-color-scheme` block. One theme.

## 3. Elevation

Elevation is a border plus a shadow, never a lighter fill on its own.

```
--e0  none                                        flush with the page
--e1  0 1px 2px rgba(0,0,0,.32)                   cards, rows
--e2  0 4px 14px rgba(0,0,0,.40)                  dropdown, popover
--e3  0 12px 34px rgba(0,0,0,.50)                 modal, sheet
--e4  0 22px 60px rgba(0,0,0,.58)                 full screen phone sheet
```

## 4. Radius and spacing

```
--r-xs 5px   pills, badges, nav items
--r-sm 8px   buttons, inputs
--r-md 12px  cards, panels
--r-lg 16px  sheets, phone modals
--r-full 999px  avatars, FAB
```

Spacing is a 4px scale: 4, 8, 12, 16, 20, 24, 32, 40, 48. Nothing in between.

## 5. Type

```
--sans -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif
--mono ui-monospace, "SF Mono", "Cascadia Code", "JetBrains Mono", Consolas, monospace
```

| Token | Size / line | Weight | Use |
|---|---|---|---|
| display | 30/1.15 | 700 | landing hero only |
| h1 | 20/1.25 | 640 | page title |
| h2 | 16/1.3 | 620 | panel head, sheet head |
| h3 | 14/1.35 | 620 | subsection |
| body | 13.5/1.5 | 450 | default |
| body-sm | 12.5/1.45 | 450 | secondary line |
| caption | 11.5/1.4 | 500 | meta, timestamps |
| overline | 10.5/1.3 | 700, .09em tracking, uppercase | nav group headings |

Tracking is negative on anything 16px and up (-.01em at 16, -.02em at 20,
-.03em at 30). Numbers in tables, counts and timestamps get
`font-variant-numeric: tabular-nums` so columns do not jitter.

On a phone every text input is **16px minimum**. Anything smaller makes iOS
Safari zoom the whole page on focus, and the page never zooms back.

## 6. Icons

One system, defined in `templates/fragments/icons.html`, used as:

```html
<svg class="ic" aria-hidden="true"><use href="#i-inbox"/></svg>
```

```css
.ic { width:18px; height:18px; flex:none; fill:none; stroke:currentColor;
      stroke-width:1.75; stroke-linecap:round; stroke-linejoin:round; }
.ic-lg { width:20px; height:20px }
.ic-sm { width:15px; height:15px; stroke-width:1.9 }
```

**No emoji. No dingbats. No text glyphs as icons. Ever.** Not `&#128274;`, not
`✉`, not `➤`, not `◆`. Those are font characters, so the device chooses how they
look - a colour padlock on Windows, a grey one on a Mac, an empty box where no
emoji font is installed. Every icon is a `<symbol>` we ship.

An icon alone is never a control on a phone unless it is one of the five bottom
tabs or a back arrow. Everything else carries a label or an `aria-label` plus a
tooltip.

## 7. Motion

```
--t-fast  120ms cubic-bezier(.2,.7,.3,1)   hover, focus, colour
--t-base  200ms cubic-bezier(.2,.7,.3,1)   sheets, panes, expand
--t-slow  320ms cubic-bezier(.2,.7,.3,1)   page-level push
```

Everything inside `@media (prefers-reduced-motion: reduce)` drops to `none`.
Never animate `width`, `height`, `top` or `left`; use `transform` and `opacity`.

## 8. Controls

- Button min height 34px desktop, **44px phone**. Primary is a filled
  `--primary`; secondary is `--panel-3` with `--border-strong`; ghost is
  transparent with no border until hover.
- Focus is always `outline: 2px solid var(--primary); outline-offset: 2px`.
  Never remove it.
- Tap targets are **44x44 minimum** below 760px, including icon buttons. If the
  visual is smaller, pad the hit area, do not grow the glyph.
- Every destructive action confirms. Every async action shows its own pending
  state on the control that was pressed, not a page-level spinner.

## 9. Empty, loading and error

Three states, always all three, never a blank box.

- **Loading**: skeleton rows that match the real row geometry. No spinner on a
  list. A spinner is only for a control.
- **Empty**: an icon at 32px in `--text-faint`, one line of what would be here,
  and the action that puts something here.
- **Error**: what failed in plain words, and a Retry that actually retries.

---

## 10. The phone mailbox

This is the part that does not exist yet and matters most.

Below 900px the sidebar rail is **gone**, not folded into a horizontal
scroller. A horizontal scroller hides its own tail: today "Install app",
"Close mailbox" and "Sign out" sit off the right edge where nobody finds them.

### Shell

```
+--------------------------------------+
| header  56px, sticky                 |   avatar . title . search . refresh
+--------------------------------------+
|                                      |
|  list, one message per row, 76px     |   scrolls
|                                      |
|                              (FAB)   |   compose, above the tab bar
+--------------------------------------+
| tab bar  56px + safe-area, fixed     |   Inbox . Folders . Compose . Search . You
+--------------------------------------+
```

- The tab bar is `position: fixed; bottom: 0` with
  `padding-bottom: env(safe-area-inset-bottom)`. The list gets matching
  `padding-bottom` so the last row is never trapped under it.
- Header uses `env(safe-area-inset-top)`.
- **Five tabs, icon over label**, label at 10.5px. Active tab is `--text` with
  the icon filled-weight; inactive is `--text-mute`.
- Opening a message pushes the reader over the list as a full-screen pane
  (`transform: translateX(100%)` to `0`). Back is a real back: it pops the
  reader, and it is wired to `history.back()` so the phone's own back gesture
  works. Without that, Android's back button exits the app from a message,
  which is the single most common way a mail PWA feels broken.
- Folders and account are bottom sheets that slide up from the tab bar, not
  centred modals.
- Compose is full screen on a phone, with the send button in the header.

### Message row, phone

76px tall. Avatar 40px on the left, deterministic colour from the address.
Line 1: sender (620 weight when unread) and time, right aligned, tabular.
Line 2: subject, one line, ellipsised.
Line 3: preview, one line, `--text-mute`.
Unread carries a 7px `--primary` dot at the left edge of the avatar, not a
separate column.

**The current row is broken and must be fixed**: the bottom border sits above
the preview line, so every preview reads as if it belongs to the message below
it. Look at any phone screenshot and the misreading is immediate.

### Message row, desktop

Single line, 44px, three columns: sender 220px, subject and preview sharing the
rest with the preview in `--text-mute` after a middot, time right aligned.
Density is the point on a desktop; three-line rows waste the screen.

---

## 11. The phone console

The console is not the phone product, but it must not be broken there.

Below 900px the console rail becomes a **slide-in drawer** behind a hamburger
in the topbar, with a scrim. Not a horizontal scroller.

Tables keep the existing card treatment (`stampTableLabels`), which works.
Charts keep measuring `clientWidth` so one SVG unit is one CSS pixel.

---

## 12. Entry, install and sign-in

### The install surface

A phone visitor who is not signed in lands on a page whose primary action is
**Install**, not "Sign in". Android and Chromium fire `beforeinstallprompt` and
get a real button. iOS Safari gets the Share sheet steps. Anything else gets
the browser-menu wording. The card is already built in `static/js/pwa.js`; what
is missing is the landing page treating install as the headline.

### One login

Today a person signs into the console with one password and then hits a second
prompt for the mailbox password, because Campaign Studio authenticates against
`app_user` and Stalwart authenticates against its own store. On a phone that is
two passwords before any mail appears, and most people only have the second.

The rule from here:

1. One form. Email and password.
2. The console provider tries `app_user` first. If it succeeds, the same
   password is offered to the mail server once, in the background. When they
   match - and for most people they will be set to match - the mailbox is
   already open and the second prompt never appears.
3. If `app_user` has no such user or the password does not match it, the
   mailbox provider tries the address against Stalwart. Success grants a
   session holding **`MAIL_READ` and `MAIL_SEND` only**, and nothing else,
   whatever role an `app_user` row might carry.
4. Landing: a mail-only session always lands on `/mail`. A console session
   lands on `/mail` from a phone and `/app` from a laptop.

Point 3 is the security boundary and it does not move. A mailbox password buys
its own mailbox and nothing else - the same thing it already buys in any IMAP
client. It never buys the ability to send a campaign.

---

## 13. What "premium" means here

Concretely, and in priority order:

1. **Nothing off-screen.** Zero horizontal bleed at 390px on every view.
2. **Nothing ambiguous.** No glyph without a label, no border that groups the
   wrong two lines, no control whose pending state is invisible.
3. **Density that matches the device.** A desktop mail list shows 20 messages,
   not 8. A phone row is tall enough to hit.
4. **One accent, used sparingly.** A screen that is mostly charcoal with one
   blue button reads as expensive. A screen with six blue slabs reads as a
   template.
5. **Motion that explains.** A pane that slides in from the right tells you
   which way back is. A fade tells you nothing.
6. **Real states.** Skeletons, empty states and errors that were designed, not
   left to the browser.

What it does not mean: gradients as surfaces, glassmorphism, drop shadows on
text, animated backgrounds, or an accent colour on every element.

---

## 14. The phone mailbox, resolved

Binding. Where this section and sections 10 or 13 disagree in detail, this one
is newer and wins; where it is silent, they still apply.

Two files change: `templates/mail.html` and `static/js/mail.js`. Everything else
listed under "Hand-off" at the end is somebody else's commit and blocks the
release.

### 14.0 The border defect: two mechanisms, both real, measured

Rendered the shipped `.list` / `.msg` / `@media(max-width:760px)` rules in
Chromium at 390x844 against a variable row count. Row 2 of N:

```
N     painted   natural   border above preview?   preview overlaps next row
 3      88.75      88            no                      -14.00 px
 8      88.75      88            no                      -14.00 px
10      71.02      75            no                       +3.73 px
12      59.19      75            no                      +15.56 px
15      47.34      75           YES                      +27.41 px
20      44.00      75           YES                      +30.75 px
40      44.00      75           YES                      +30.75 px
```

At 800px wide with the same 40 rows: painted 84.75, natural 84. No crush.

Read that table before touching anything, because it explains why three
independent audits disagreed. **Mechanism one, dominant.** `.list` is
`display:flex; flex-direction:column` with a definite height, so every `.msg` is
a flex item with `flex-shrink:1`. The only guard is the flex automatic minimum
size, which resolves `min-height:auto` to the item's content height. The
`@media(max-width:760px)` block sets `.msg{min-height:44px}` for tap targets,
which **overwrites `min-height:auto` and switches the guard off**. Rows then
shrink to `listHeight / N`, clamping at 44px. A real inbox pages at 40, so every
phone row is permanently 44px tall holding 75px of text: the hairline is painted
12px above the preview's own top edge, and each preview sits 30.75px inside the
next message's box. That is the reported bug, and it is invisible under any
fixture with eight rows or fewer.

**Mechanism two, residual.** When the list does not overflow, rows render at
their natural 88.75px and the hairline sits dead centre of a 26px gutter, about
17px of clear space from the preview above and about 18px from the sender below.
A rule equidistant from both neighbours groups neither, so the preview still
floats free and the eye attaches it to the bold sender under it.

**Fix both.** `.list{display:block}` removes the crush (verified: 40 rows at
390px, painted 88.75 against natural 88, no overlap). The row rebuild in 14.4
removes the ambiguity. Neither alone is sufficient: the first leaves a rule that
groups nothing, the second leaves rows that crush.

### 14.1 Decisions where the three proposals conflicted

| Question | Decision | Why, in one line |
|---|---|---|
| Border mechanism | Both, fixed together | The crush appears only past nine rows, and a 40-message inbox is always past nine. |
| Shell spine | Separate phone header, one state object, one reconciler | Reusing `.topbar` as the phone header needs a 24-cell visibility matrix that no reader can hold in their head. |
| Phone header position | Flow child of `.main`, `flex:none`, never absolute or sticky | An absolute header forces the list to carry a compensating `padding-top` that must change when search opens, and that pair drifts. |
| Tab bar | `position:fixed; bottom:0`, direct child of `<body>` | Section 10 is binding, and body-level because `.reader` carries a transform, which would make it the containing block for any fixed descendant. |
| Reader pane | `position:absolute; inset:0` inside `.mail`, tab bar slides down | Honest stacking beats escaping two `overflow:hidden` ancestors with `position:fixed`. |
| The FAB | The Compose tab **is** the FAB, a cradled disc raised out of the bar | Section 10's diagram asks for a compose FAB and a Compose tab, which is one action twice; one element satisfies both and covers no list row. |
| `viewport-fit=cover` | Add it | Without it the tab bar's own surface stops at the safe-area edge and leaves a 34px band of mismatched charcoal under it on an iPhone. |
| Avatar colour | Eight-slot CSS palette keyed by `data-c` | A fixed palette can be contrast-checked once and verified; a per-address derived hue cannot be verified for every address. |
| Search field | Move the single `#q` node between two slots | `appendChild` preserves listeners and value; two mirrored inputs are duplicated state that desyncs on rotation. |
| Swipe to archive or delete | Not built | Section 8 requires every destructive action to confirm, and a confirm sheet interrupting a gesture is worse than a button. |
| Pull to refresh | Not built | It needs a non-passive `touchmove` fighting native overscroll, and Refresh is already a labelled 44px control in the header. |
| Scroll restore after Back | `.list{overflow:hidden}` while reading, never `display:none` | The offset survives for free and cannot desync, unlike storing `scrollTop` in the history entry. |
| Infinite scroll | Not built | Load more already works and pages at 40; nothing measured justifies replacing it. |

### 14.2 DOM skeleton

Exact class and id names. Ids that already exist carry live listeners: do not
rename them. `mail.html` `<head>` changes first:

```html
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
...
<script src="/js/mail.js?v=phone1"></script>
```

```html
<body>
<th:block th:replace="~{fragments/icons :: sprite}"></th:block>

<div class="app">

  <!-- display:none below 900px. Not removed, so no listener needs a null guard. -->
  <aside class="rail">
    ... unchanged, except the six &#nnnn; glyphs become sprite icons per 14.7 ...
    <a class="nav" href="/mail" aria-current="true">
      <svg class="ic" aria-hidden="true"><use href="#i-inbox"/></svg>
      <span>Inbox</span><span class="badge" id="railUnread">0</span></a>
    <button class="nav" type="button" id="railCompose">
      <svg class="ic" aria-hidden="true"><use href="#i-compose"/></svg><span>Compose</span></button>
    <a class="nav" href="/app">
      <svg class="ic" aria-hidden="true"><use href="#i-campaign"/></svg><span>Campaign Studio</span></a>
    <button class="nav" type="button" data-jm-install>
      <svg class="ic" aria-hidden="true"><use href="#i-install"/></svg><span>Install app</span></button>
    <button class="nav" type="button" id="railLock">
      <svg class="ic" aria-hidden="true"><use href="#i-lock"/></svg><span>Close mailbox</span></button>
    <a class="nav" href="/logout">
      <svg class="ic" aria-hidden="true"><use href="#i-logout"/></svg><span>Sign out</span></a>
    <div class="railfoot"><div class="avatar" id="railAvatar">?</div><span id="railEmail">signed in</span></div>
  </aside>

  <main class="main">

    <!-- Phone header. A flow child of .main with flex:none, 56px plus the top
         inset. Two rows, one visible at a time, switched by body.searching.
         It is display:none above 900px, where .topbar is the header instead. -->
    <header class="phead" id="phead">
      <div class="phead-row" data-mode="title">
        <button class="pib" type="button" id="pbAccount" aria-label="Account and settings">
          <span class="avatar av-sm" id="pheadAvatar" data-c="0"
                th:text="${userEmail != null and !userEmail.isEmpty()}
                         ? ${#strings.toUpperCase(#strings.substring(userEmail,0,1))} : '?'">?</span>
        </button>
        <h1 class="ptitle" id="pheadTitle">Inbox</h1>
        <button class="pib" type="button" id="pbSearch" aria-label="Search this mailbox">
          <svg class="ic ic-lg" aria-hidden="true"><use href="#i-search"/></svg></button>
        <button class="pib" type="button" id="pbRefresh" aria-label="Refresh">
          <svg class="ic ic-lg" aria-hidden="true"><use href="#i-refresh"/></svg></button>
      </div>
      <div class="phead-row" data-mode="search" id="pheadSearch">
        <button class="pib" type="button" id="pbSearchBack" aria-label="Close search">
          <svg class="ic ic-lg" aria-hidden="true"><use href="#i-back"/></svg></button>
        <!-- #q is moved into this slot on a phone and back to #qDesk above 900px.
             One node, one listener, one value: a second input would desync on the
             first rotation. -->
        <span class="qslot" id="qPhone"></span>
      </div>
    </header>

    <!-- Existing desktop topbar, display:none below 900px. -->
    <div class="topbar">
      <h1 id="paneTitle">Inbox</h1>
      <span class="qslot" id="qDesk">
        <input class="search" id="q" type="search" inputmode="search" enterkeyhint="search"
               placeholder="Search this mailbox..." autocomplete="off">
      </span>
      <button class="btn sm" type="button" id="btnRefresh">Refresh</button>
      <span class="spacer"></span>
      <button class="btn pri" type="button" id="btnCompose">Compose</button>
    </div>

    <div class="mail" id="mailGrid">
      <div class="folders" id="folders"></div>

      <!-- The one scrolling box on a phone. Ten skeleton rows ship in the
           template so first paint is never blank and costs no JavaScript;
           renderList replaces the whole subtree. -->
      <div class="list" id="list" aria-busy="true">
        <div class="skel" aria-hidden="true">
          <div class="skrow"><span class="sk sk-av"></span><span class="skb">
            <span class="sk sk-1"></span><span class="sk sk-2"></span><span class="sk sk-3"></span></span></div>
          <!-- repeat the .skrow above ten times -->
        </div>
      </div>

      <!-- Always in the DOM, parked at translateX(100%). Never display:none:
           a pane that does not exist cannot slide. -->
      <section class="reader" id="reader" aria-hidden="true">
        <div class="rbar">
          <button class="pib" type="button" data-act="back" aria-label="Back to the message list">
            <svg class="ic ic-lg" aria-hidden="true"><use href="#i-back"/></svg></button>
          <span class="rbar-t" id="rbarTitle">Inbox</span>
          <span class="spacer"></span>
          <button class="pib" type="button" data-act="flag" id="rStar" aria-label="Star this message">
            <svg class="ic ic-lg" aria-hidden="true"><use href="#i-star"/></svg></button>
          <button class="pib" type="button" data-act="move" aria-label="Move to folder">
            <svg class="ic ic-lg" aria-hidden="true"><use href="#i-folder-move"/></svg></button>
          <button class="pib" type="button" data-act="delete" aria-label="Delete this message">
            <svg class="ic ic-lg" aria-hidden="true"><use href="#i-trash"/></svg></button>
        </div>
        <div class="rbody" id="rbody"><!-- .rhead then .rwrap > .rframe --></div>
        <button class="fab fab-in-pane" type="button" data-act="reply" aria-label="Reply">
          <svg class="ic ic-lg" aria-hidden="true"><use href="#i-reply"/></svg></button>
      </section>
    </div>
  </main>
</div>

<!-- Body-level. Anything position:fixed must live outside .mail, because
     .reader carries a transform and would capture it. -->
<nav class="tabbar" id="tabbar" aria-label="Mailbox">
  <button class="tab" type="button" data-tab="inbox" aria-current="page">
    <span class="tab-ic"><svg class="ic" aria-hidden="true"><use href="#i-inbox"/></svg>
      <span class="tab-badge" id="tabUnread" hidden>0</span></span>
    <span class="tab-l">Inbox</span></button>
  <button class="tab" type="button" data-tab="folders">
    <span class="tab-ic"><svg class="ic" aria-hidden="true"><use href="#i-folder"/></svg></span>
    <span class="tab-l">Folders</span></button>
  <button class="tab tab-fab" type="button" data-tab="compose" id="tabCompose">
    <span class="tab-ic"><svg class="ic" aria-hidden="true"><use href="#i-compose"/></svg></span>
    <span class="tab-l">Compose</span></button>
  <button class="tab" type="button" data-tab="search">
    <span class="tab-ic"><svg class="ic" aria-hidden="true"><use href="#i-search"/></svg></span>
    <span class="tab-l">Search</span></button>
  <button class="tab" type="button" data-tab="you">
    <span class="tab-ic"><svg class="ic" aria-hidden="true"><use href="#i-user"/></svg></span>
    <span class="tab-l">You</span></button>
</nav>

<div class="backdrop bsheet" id="foldersSheet">
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="foldersSheetTitle">
    <span class="grab" aria-hidden="true"></span>
    <div class="sheet-h"><h3 id="foldersSheetTitle">Folders</h3><span class="spacer"></span>
      <button class="pib" type="button" data-close aria-label="Close">
        <svg class="ic" aria-hidden="true"><use href="#i-close"/></svg></button></div>
    <div class="sheet-b" id="sheetFolders"></div>
  </div>
</div>

<!-- This sheet is the literal fix for the reported defect: Install app, Close
     mailbox and Sign out were parked 380px, 496px and 633px into a 390px rail
     that hides its own scrollbar. They are four full-width 52px rows here. -->
<div class="backdrop bsheet" id="accountSheet">
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="accountSheetTitle">
    <span class="grab" aria-hidden="true"></span>
    <div class="sheet-h">
      <span class="avatar av-lg" id="sheetAvatar" data-c="0">?</span>
      <div class="who"><b id="accountSheetTitle">Signed in</b>
        <span class="muted" id="sheetEmail">signed in</span></div>
      <span class="spacer"></span>
      <button class="pib" type="button" data-close aria-label="Close">
        <svg class="ic" aria-hidden="true"><use href="#i-close"/></svg></button></div>
    <div class="sheet-b sheet-menu">
      <a class="mrow" href="/app" id="shStudio">
        <svg class="ic" aria-hidden="true"><use href="#i-campaign"/></svg><span>Campaign Studio</span>
        <svg class="ic ic-sm chev" aria-hidden="true"><use href="#i-next"/></svg></a>
      <button class="mrow" type="button" data-jm-install>
        <svg class="ic" aria-hidden="true"><use href="#i-install"/></svg><span>Install app</span></button>
      <button class="mrow" type="button" id="shLock">
        <svg class="ic" aria-hidden="true"><use href="#i-lock"/></svg><span>Close mailbox</span></button>
      <a class="mrow danger" href="/logout">
        <svg class="ic" aria-hidden="true"><use href="#i-logout"/></svg><span>Sign out</span></a>
    </div>
  </div>
</div>

<div class="backdrop" id="composeSheet">
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="composeTitle">
    <div class="sheet-h">
      <button class="pib" type="button" id="btnCancelTop" aria-label="Discard and close">
        <svg class="ic ic-lg" aria-hidden="true"><use href="#i-close"/></svg></button>
      <h3 id="composeTitle">New message</h3>
      <span class="spacer"></span><span class="pill ac" id="composeFrom">from</span>
      <span class="qslot" id="sendHead"></span>
    </div>
    <div class="sheet-b">... existing fields, ids cTo cCc cSubject cBody unchanged ...</div>
    <div class="sheet-f">
      <span class="qslot" id="sendFoot"><button class="btn pri" type="button" id="btnSend">Send</button></span>
      <button class="btn" type="button" id="btnCancel">Cancel</button>
      <span class="spacer"></span>
      <span class="muted" style="font-size:12px">Sends through the jarurat.care mail server</span>
    </div>
  </div>
</div>

<div class="backdrop" id="unlockSheet">... unchanged, and deliberately outside the pane machine ...</div>
<div id="toasts"></div>
```

Message row markup emitted by `renderList`:

```html
<button type="button" class="msg unread" data-id="...">
  <span class="av" data-c="4" aria-hidden="true">P</span>
  <span class="txt">
    <span class="r1"><span class="from">Priya Sharma</span><span class="when">22:27</span></span>
    <span class="subj">Draft brochure copy for the Carcinome print run</span>
    <span class="prev">I went through the copy you sent last night...</span>
  </span>
  <span class="marks" aria-hidden="true"><svg class="ic ic-sm"><use href="#i-attach"/></svg></span>
</button>
```

Rules that are load-bearing in that skeleton:

- Sprite `<th:block>` is the first child of `<body>`, once per page.
- **No `role="tablist"` and no `aria-selected` on the tabs.** Three of the five
  open an overlay rather than swapping a panel, and `tablist` promises arrow-key
  panel navigation that does not exist. `aria-current="page"` on the active one.
- Every child of `.msg` is a `<span>`, never a `<div>`: a `<button>` takes
  phrasing content, and the shipped row puts `<div>`s inside one.
- `#btnSend` and `#q` are single nodes relocated between `.qslot` spans by
  `placeChrome()` on a `matchMedia` change. `appendChild` on an existing node
  keeps its listeners, so one rotation mid-compose does not lose the draft or
  the pending state.
- The install rows keep `data-jm-install`; `pwa.js` already delegates on
  `closest('[data-jm-install]')` and needs no change.

### 14.3 Breakpoints, exactly

| Query | What changes |
|---|---|
| `@media (min-width:900px) and (max-width:1100px)` | The existing tablet block, **narrowed from `max-width:1100px`**. Contents unchanged: `.mail{grid-template-columns:1fr}`, `.folders{display:none}`, `.mail .reader{display:none}`, `.mail.reading .list{display:none}`, `.mail.reading .reader{display:flex}`, `.btnback{display:inline-flex}`. Narrowing is mandatory: `display:none` cannot be transformed, so an unnarrowed block kills the phone slide. |
| `@media (max-width:900px)` (the rail scroller) | **Delete the whole block.** It is the defect: `aside.rail` measures scrollWidth 843 against clientWidth 390 at 390px, and `scrollbar-width:none` plus `::-webkit-scrollbar{height:0}` hides the only hint that a tail exists. |
| `@media (max-width:899.98px)` (new, placed last) | The entire phone shell: `.rail{display:none}`, `.app{grid-template-columns:1fr}`, `.phead{display:flex}`, `.topbar{display:none}`, `.tabbar{display:grid}`, `.mail{display:block;position:relative;overflow:hidden}`, `.folders{display:none}`, `.list{display:block}` and the whole list geometry, the reader pane, the sheets, full-screen compose, the FAB tab, `.pib`, `.btn{min-height:44px}`, and **the 16px input rule lifted out of the 760 block** so an 850px tablet does not get a tab bar and iOS-zooming inputs at once. Use 899.98 and not 900, because `max-width:900px` and `min-width:900px` both match at exactly 900. |
| `@media (max-width:760px)` | Keep only the small-phone type lift and `.att`, `.fold`, `.pill`, `#toasts`. **Delete `.msg{min-height:44px;padding:13px 14px}`** (that line is the crush trigger) and **delete `.rmeta .avatar{display:none}`** (the reader avatar is now the colour chip and belongs there). |
| `@media (prefers-reduced-motion:reduce)` | `.reader`, `.tabbar`, `.backdrop`, `.sheet`, `.fab`, `.sk` and `.spin` all drop to `transition:none; animation:none`. |

Add the three motion tokens from section 7 to `:root` in `mail.html`
(`--t-fast`, `--t-base`, `--t-slow`); they do not exist there yet. Add
`--r-lg:16px`. Change nothing else in that token block: its names and values are
copied from `static/css/style.css`, and reconciling the palette against section
2 is a separate pass that also touches the console.

Add the section 6 icon base verbatim, plus one line the DOM needs:

```css
.ic{width:18px;height:18px;flex:none;fill:none;stroke:currentColor;stroke-width:1.75;
    stroke-linecap:round;stroke-linejoin:round;pointer-events:none}
.ic-lg{width:20px;height:20px}
.ic-sm{width:15px;height:15px;stroke-width:1.9}
.ic-32{width:32px;height:32px;stroke-width:1.5;color:var(--text-faint)}
```

`pointer-events:none` is not cosmetic; see 14.8. And `.nav .ic{width:16px;
text-align:center;opacity:.85;flex:none}` becomes `.nav .ic{width:16px;
height:16px;opacity:.85;flex:none}`: `text-align` is meaningless on an SVG and a
width without a height squashes it.

### 14.4 Message row geometry

**Phone, 76px, exactly.** Three columns, `align-items:start`.

```
grid-template-columns: 40px 1fr auto ;  column-gap: 12px
padding: 16px 16px 8px            (left 16, so the hairline inset is 68px)
line 1  .r1    18px line-height   sender 14px / 450, unread 620 ; time 11.5px tabular
line 2  .subj  18px line-height   13.5px, one line, ellipsised
line 3  .prev  16px line-height   12.5px --text-mute, display:block, one line
                                  16 + 18 + 18 + 16 + 8 = 76
```

`.prev` must be `display:block`. Left as an inline span the block strut adds 2px
and the row measures 78 instead of 76. Text column at 390px is
390 - 16 - 40 - 12 - 16 = 306px, of which the timestamp takes about 44px.

**The border fix, stated explicitly. Three changes, all required.**

1. `.list{display:block}` inside the phone block. This is the crush fix. `.list`
   was flex only so `.listfoot` sat after the rows, which normal block flow
   already does. Also set `.msg{flex:none}` so the defect cannot return through
   a future container change.
2. The hairline is a pseudo-element inset to the text column, never
   `border-bottom`:
   ```css
   .msg{position:relative}
   .msg::after{content:"";position:absolute;left:68px;right:0;bottom:0;
               height:1px;background:var(--border)}
   .msg:last-of-type::after{display:none}
   ```
   A full-bleed rule under a row that has a leading avatar reads as the top edge
   of the row below it. Inset to where the text starts, it can only read as the
   bottom edge of this one.
3. Padding is 16 top against 8 bottom, never 12 and 12. Measured on the shipped
   row, the rule sat about 17px of clear space below the preview and about 18px
   above the next sender, a 1:1.05 ratio that groups neither side. At 16/8 the
   gap below the preview ink is about 9px and above the next sender ink about
   18px, close to 1:2.

Unread is a 7px `--primary` dot straddling the avatar's left rim, not a column
and not the current `.from::before` inline dot, which must be deleted:

```css
.msg .av{position:relative}
.msg.unread .av::after{content:"";position:absolute;left:-4px;top:50%;
    width:7px;height:7px;margin-top:-3.5px;border-radius:50%;
    background:var(--primary);box-shadow:0 0 0 2px var(--panel)}
.msg.unread .from::before{content:none}
```

The 2px ring in the row ground punches the dot out of the avatar so it reads as
an overlay rather than a smudge on the rim.

`.marks` is the third grid cell, never `float:right` inside `.subj`. Measured on
the shipped row, `.subj` has `scrollWidth 430` against `clientWidth 362`, and
`text-overflow` is computed ignoring floats, so the ellipsised subject currently
renders underneath the paperclip. A grid or flex sibling cannot overlap.

Also set `-webkit-tap-highlight-color:transparent` on `.msg`, `.tab`, `.pib`,
`.mrow` and `.fold` with an explicit `:active{background:var(--panel-3)}`.
Without it Android paints its own grey flash over the designed pressed state.

**Desktop, 44px, one line, same markup.** Use `display:contents` so no wrapper
survives into the flex row:

```css
@media (min-width:900px){
  .msg{display:flex;align-items:center;gap:10px;min-height:44px;padding:0 14px;
       border-bottom:1px solid var(--border-soft)}
  .msg .av{display:none}
  .msg .txt,.msg .r1{display:contents}
  .msg .from{flex:0 0 220px;width:220px}
  .msg .subj{flex:0 1 auto;min-width:0}
  .msg .prev{flex:1 1 auto;min-width:0;color:var(--text-mute)}
  .msg .prev::before{content:"\00b7";margin:0 8px;color:var(--text-faint)}
  .msg .marks{order:8}
  .msg .when{order:9;margin-left:auto}
  .msg::after{display:none}
}
```

Skeleton rows reuse the same numbers so real rows never jump when they land:
`.skrow{display:grid;grid-template-columns:40px 1fr;column-gap:12px;
min-height:76px;padding:16px 16px 8px;position:relative}` with the same
`::after` at `left:68px`. Bar widths rotate over three values by
`:nth-child(3n+1|2|3)` in CSS, never by `Math.random`, so the skeleton is stable
across renders and does not read as a barcode. The shimmer animates
`background-position` only; section 7 forbids animating width or height.

### 14.5 The five bottom tabs

`position:fixed; left:0; right:0; bottom:0; z-index:60`, direct child of
`<body>`, `height:calc(56px + env(safe-area-inset-bottom,0px))` with
`padding-bottom:env(safe-area-inset-bottom,0px)`. `.list` carries the matching
`padding-bottom:calc(56px + env(safe-area-inset-bottom,0px) + 8px)`. Layout is
`display:grid; grid-auto-flow:column; grid-auto-columns:1fr`, so hiding
`#tabCompose` when `!can('MAIL_SEND')` redistributes the remaining four with no
extra rule. Each cell is 78x56 at 390px, past the 44x44 floor.

| Order | `data-tab` | Label | Sprite id | Action |
|---|---|---|---|---|
| 1 | `inbox` | Inbox | `#i-inbox` | If a pane or overlay is open, `history.back()`. If already on the list, smooth-scroll `#list` to top. Carries `#tabUnread`. |
| 2 | `folders` | Folders | `#i-folder` | `go({overlay:'folders'})`, then render `#sheetFolders` |
| 3 | `compose` | Compose | `#i-compose` | `go({overlay:'compose'})` and `openCompose()`. This tab is the FAB. |
| 4 | `search` | Search | `#i-search` | `go({overlay:'search'})`, then focus `#q` |
| 5 | `you` | You | `#i-user` | `go({overlay:'account'})` |

The sprite is stroke-only, so "filled weight" for the active tab is a heavier
stroke plus full-strength text, never colour alone:
`.tab[aria-current="page"]{color:var(--text)}` and
`.tab[aria-current="page"] .ic{stroke-width:2.3}`, inactive `--text-mute`, label
10.5px at 500 weight.

The Compose tab is the cradled FAB: a 48px `--primary` disc with
`margin-top:-16px` inside `.tab-ic`, `#i-compose` in white, `box-shadow:0 6px
16px rgba(0,0,0,.45)`. The whole 78x56 tab cell stays the hit target. There is
no second floating FAB anywhere on the list; `.fab-in-pane` exists only inside
`.reader` as Reply, `position:absolute` against that pane.

`#tabUnread` is `hidden` at zero and shows `n > 99 ? '99+' : n`, fed by the same
reduce that already sets `#railUnread` in `renderFolders`.

Two overrides that stay invisible until an iPhone finds them:
`#toasts{bottom:calc(56px + env(safe-area-inset-bottom,0px) + 12px);left:12px;
right:12px;max-width:none}`, and `body #jmInstall{bottom:calc(68px +
env(safe-area-inset-bottom,0px))}`. `pwa.js` injects its own stylesheet at
runtime and parks the install card at `z-index:9998; bottom:calc(12px + env(...))`
where it covers the tab bar; `body #jmInstall` outranks it on specificity
without `!important` and without touching that file.

### 14.6 Pane transition and history

**Reader pane.** Inside the phone block:

```css
.mail{display:block;position:relative;overflow:hidden;min-height:0}
.reader{position:absolute;inset:0;z-index:30;display:flex;flex-direction:column;
        background:var(--panel);
        transform:translateX(100%);visibility:hidden;
        transition:transform var(--t-base), visibility 0s linear 200ms}
body[data-pane="reader"] .reader{transform:translateX(0);visibility:visible;
        transition:transform var(--t-base), visibility 0s}
body[data-pane="reader"] .list{overflow:hidden}
body[data-pane="reader"] .tabbar{transform:translateY(100%)}
.tabbar{transition:transform var(--t-base)}
.btnback{display:none}          /* the .rbar back button replaces it below 900 */
```

Four details in that block are each a bug if changed:

- The open state is `translateX(0)` and **never `transform:none`**. A transform
  other than `none` makes the element the containing block for fixed
  descendants; if the open state is `none`, that behaviour appears for 200ms
  during the slide and vanishes on arrival, which cannot be reproduced on demand
  in devtools. For the same reason never put a transform on `.app` or `.main`.
- `visibility` is paired with the transform and its transition is asymmetric,
  `0s linear 200ms` closing and `0s` opening. A pane parked at
  `translateX(100%)` alone stays tab-focusable, stays in the accessibility tree,
  and contributes horizontal overflow that Chrome and Safari will paint a
  scrollbar for. Set `inert` on it from `applyState` as well; `visibility` is
  the portable half for older Android WebView.
- `.list` gets `overflow:hidden` while reading, never `display:none`, so the
  scroll offset survives the round trip for free. That is the difference between
  Back returning you to where you were and Back dumping you at the top.
- The tab bar slides down rather than being covered. A body-level fixed bar at
  z-index 60 outranks a z-index 30 pane inside an untransformed ancestor, so
  covering it would need a stacking fight; every native mail client hides its
  bottom chrome on a detail push anyway.

**History.** One object, one reconciler, one pusher, one closer. This is the
whole point of the change, and section 10 makes it non-negotiable.

```js
/* The phone shell is a small state machine whose only storage is the history
   stack, because in display:standalone the phone's own back gesture is the only
   back there is. applyState is the sole DOM mutator, go() is the sole pusher,
   and everything that means "back" calls history.back(). Splitting that rule is
   how a back button starts needing two taps. */
const BASE = { pane: 'list', overlay: null, id: null };
const UI = Object.assign({}, BASE);

function applyState(s) { /* absolute, never incremental: sets every class from s */ }
function go(patch) { history.pushState({ jm: Object.assign({}, UI, patch) }, ''); applyState(UI); }
window.addEventListener('popstate', e => applyState((e.state && e.state.jm) || BASE));
history.replaceState({ jm: BASE }, '');   // at boot, before anything can push
```

`applyState` sets, on every call and from the state alone:
`document.body.dataset.pane`, `body.classList.toggle('searching', ...)`,
`$('mailGrid').classList.toggle('reading', ...)` so the untouched tablet block
keeps working, `.open` on exactly one of `#foldersSheet` / `#accountSheet` /
`#composeSheet`, `inert` on `#reader` and on `#list`, `aria-current` on the five
tabs, and focus. It is absolute because Android back can land on any state from
any other: with the reader open and compose over it the stack is
`[list, reader, compose]`, and a handler that closes "the topmost thing" cannot
know what it is now in.

Rules an implementer must follow or the feature ships with the defect it was
meant to fix:

1. **Never mutate and pop in the same path.** A close handler that both removes
   the class and calls `history.back()` makes `popstate` close a second time and
   eat the entry below, so Back leaves the app one press early.
2. **Do not push when there is nothing to pop.** Above 1100px both panes are on
   screen, so guard reader pushes with
   `matchMedia('(max-width:1100px)').matches`, evaluated at call time and never
   cached: rotating the phone changes the answer.
3. **Audit every caller for `push:false`.** `openMessage(id, {push:false})` for
   the re-open after `toggleFlag`, for "Show images", and for a forward
   `popstate`. Miss one and Back needs two presses after starring a message.
   This is the single most likely defect in the feature.
4. **`moveMessage` and `removeMessage` call `history.back()`**, not
   `applyState`, so the reader's entry is consumed rather than replaced.
   Replacing it leaves `[list, list]` on the stack and Back appears to do
   nothing.
5. **Clear the `#compose` deep link.** After honouring
   `location.hash === '#compose'`, call
   `history.replaceState({jm:{pane:'list',overlay:'compose',id:null}}, '', '/mail')`,
   or the first Back returns to `/mail#compose` and reopens compose forever.
   `popstate` also fires for hash changes, so `applyState(BASE)` must be a valid,
   safe, everything-closed state.
6. **`popstate` is not cancellable.** If compose has a non-empty body on pop,
   push the compose entry straight back and show the confirm, behind a re-entry
   guard so the re-push does not run the handler's own logic.
7. **The unlock sheet stays outside the machine.** It has no dismiss by design,
   a 409 can arrive at any moment including while the reader is open, and it
   must never be poppable by a back gesture.
8. **Focus.** `applyState` records `document.activeElement` on the way into a
   pane and restores it on the way out, or Back strands the focus ring behind a
   closed overlay.

**Safe areas.** `viewport-fit=cover` is now on the meta, so every `env()` is
live on iOS. Write all of them two-argument, `env(safe-area-inset-bottom, 0px)`,
so a browser that knows `env()` but not the keyword still parses the
declaration. The horizontal insets are not optional either: `portrait-primary`
in the manifest is honoured only by Android standalone, and iOS rotates
regardless. The top inset stays 0 in the installed iOS app because
`fragments/pwa.html` sets `apple-mobile-web-app-status-bar-style: black`, which
asks iOS to reserve the strip. Do not change that fragment; the top padding is
simply a no-op there and correct in Safari, in landscape and on Android.

### 14.7 Every glyph becomes a sprite symbol

The audit counted 8 characters above U+2000 on `/mail` at 390px and 15 at
1440px. Target is zero. All 69 symbols exist in `fragments/icons.html`,
including `i-settings` and `i-mail-open`.

| Current | Where | Replacement |
|---|---|---|
| `FOLDER_ICON = {inbox:'envelope', sent:'arrow', drafts:'pencil', junk:'flag', trash:'cross', archive:'tray'}` as literal characters | `mail.js` | `{inbox:'i-inbox', sent:'i-send', drafts:'i-draft', junk:'i-spam', trash:'i-trash', archive:'i-archive'}` |
| `'\u25CF'` folder default | `renderFolders` | `i-folder` |
| `'\u{1F4CE}'` paperclip, `'\u2605'` star | `renderList` marks | `i-attach`, `i-star-on`, both `ic-sm` |
| `'&#9888;'` | `errState` | `i-warn` at `ic-32` in `--danger-fg` |
| `'\u2709'` in `.empty .big`, four call sites | `mail.js` | `i-mail` at `ic-32` |
| `'\u25CB'` empty folder | `renderList` | `i-inbox` at `ic-32` |
| `'\u26A0'` blocked images | `renderReader` | `i-warn` at `ic-sm` |
| `&#9993; &#9998; &#9670; &#8623; &#128274; &#8617;` | rail, `mail.html` | `i-mail`, `i-compose`, `i-campaign`, `i-install`, `i-lock`, `i-logout` |

`&#128274;` is the colour Windows padlock in the screenshot. `.empty .big{font-size:26px}`
is deleted and replaced by `.ic-32`, which also clears the audit's 2.05:1
contrast failure on `div#reader>div.empty>span.big` at 1440px, since
`--text-faint` reads 4.60:1 on `--panel` against a 3:1 floor for a graphic.
Empty states take section 9's shape: a 32px icon, one line of what would be
here, and the action that puts something here. The one glyph outside these two
files is `pwa.js`'s close button.

The star control swaps its symbol, never its text:
```js
$('rStar').querySelector('use').setAttribute('href', m.flagged ? '#i-star-on' : '#i-star');
$('rStar').setAttribute('aria-label', m.flagged ? 'Remove star' : 'Star this message');
```

**Avatar colour.** Eight slots, painted by eight CSS rules keyed on `data-c`, so
the palette is auditable in the stylesheet rather than scattered through inline
styles. Check every ground at 5.6:1 or better against its own initial and 1.45:1
or better against `--panel`, and use no hue between 195 and 250, so an avatar can
never be mistaken for `--primary` or for the unread dot.

```js
/* Derived, not assigned, so the same sender is the same colour in every folder,
   on every device and after every reload, with no state stored anywhere.
   Math.imul is not optional: a plain multiply overflows past 2^53 and the low
   bits stop moving, which collapses the hash into a handful of buckets. */
function avatarSlot(address) {
  const s = String(address || '').toLowerCase();
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193); }
  return (h >>> 0) & 7;
}
/* Array.from, not [0]: a display name starting outside the BMP would otherwise
   be sliced through a surrogate pair and render as a replacement box. */
function avatarInitial(display, email) { /* first letter or digit, else '?' */ }
```

The same derivation feeds `#pheadAvatar`, `#sheetAvatar`, `#railAvatar` and the
reader's `.rmeta .avatar` from `ME.email`. `.mrow.danger` uses `--danger-fg`
(#f2776b), never `--danger` (#e0483c); the audit already has `--danger` failing
at 4.00:1 as text elsewhere.

### 14.8 Three things that break silently, in order of likelihood

**1. Converting `data-act` buttons to sprites kills every reader action.**
`renderReader` currently reads `e.target.getAttribute('data-act')`. With a text
label the tap target is the button; with `<svg class="ic"><use href="#i-reply"/></svg>`
inside it, `e.target` is the `<svg>`, the attribute is null, and every reader
action stops working with nothing in the console. `<use>` shadow content can
also surface as the target in some engines. Both halves are required:
`pointer-events:none` on `.ic` in CSS, and `e.target.closest('[data-act]')` in
JS. Apply the same to `.fold`, `.msg`, `.tab`, `.mrow` and `.pib`. Replace the
per-render `head.addEventListener` with one delegated listener bound once on
`#reader`, which also removes the listener leak on every re-render.

**2. The message body is an iframe, and it is a black hole for height.**
`mountBody` builds a sandbox with no `allow-same-origin` and no `allow-scripts`,
correctly, so the frame can never report its content height and iOS Safari sizes
it to that content while ignoring the CSS height. A long message then stretches
the reader pane instead of scrolling inside it, the internal scroll never
engages, and `.rbar` scrolls away with nothing to bring it back. The fix is a
wrapper that scrolls while the frame keeps its natural height, and it belongs in
`mountBody`:

```js
function mountBody(container, doc) {
  const old = container.querySelector('.rwrap');
  if (old) old.remove();          // two 400KB srcdoc documents per open is a real leak
  const wrap = document.createElement('div');
  wrap.className = 'rwrap';       // flex:1; min-height:0; overflow:auto; overscroll-behavior:contain
  const frame = document.createElement('iframe');
  frame.className = 'rframe';
  frame.setAttribute('sandbox', 'allow-popups allow-popups-to-escape-sandbox');
  frame.setAttribute('referrerpolicy', 'no-referrer');
  frame.setAttribute('title', 'Message body');
  wrap.appendChild(frame);
  container.appendChild(wrap);
  frame.srcdoc = doc;             // still a property, never concatenated
}
```

The sandbox list, the `referrerpolicy` and the property assignment of `srcdoc`
are the security boundary documented in `MailHtmlSanitizer`. None of it moves.
Mount the frame after the slide finishes: a srcdoc frame first laid out inside a
`visibility:hidden` subtree sometimes never paints on WebKit. `transitionend`
does not fire when the element was already at its target and is absent entirely
under reduced motion, so pair it with a 260ms timeout and take whichever fires
first.

**3. The service worker will serve the old `mail.js` against the new
`mail.html`.** `sw.js` precaches `/js/mail.js` stale-while-revalidate and
deliberately never caches HTML. On the first launch after deploy an installed
phone therefore gets the new template with the previous script: `#tabbar` has no
wiring, `<body>` never gets `data-pane`, the reader never slides, and it reads
as a total deployment failure. Both ends are needed: bump `VERSION` from
`'jm-v1'` in `sw.js`, which is somebody else's file, and request
`/js/mail.js?v=phone1` from `mail.html`, which changes the cache key even if the
worker itself has not updated yet.

Below those three: give `.list` and both sheets `overscroll-behavior:contain` so
scroll never chains to the shell; keep `.app{height:100dvh;overflow:hidden}`
with `.list` as the only scrolling box, so `dvh`, `svh` and `lvh` stay equal and
Safari's URL bar never triggers a mid-scroll resize; render the reader head from
the row already in `S.messages` before the fetch, so the correct sender and
subject are on screen in the same frame as the tap and only the body waits; and
fire `/api/mail/status` and `/api/mail/folders` together at boot instead of in
series, with `handled()` returning true while `S.booting` so a boot-time 409
cannot race the status answer into a double unlock prompt.

### 14.9 Deliberately not built

Swipe to archive or delete, pull to refresh, a second floating FAB, a phone
drawer for the rail, virtualised list rendering, conversation threading,
multi-select with batch actions (`/api/mail/move` and `/api/mail/delete` each
take one id, so "archive 40" is 40 round trips with 40 ways to half-fail),
draft autosave (there is no drafts endpoint), offline or `sessionStorage`
caching of mail (the mailbox is password-gated per session on a device that gets
handed around), push notifications, a custom edge-back gesture, drag-to-dismiss
on sheets, `visualViewport` keyboard choreography, a light theme, and a separate
phone template.

### 14.10 Definition of done

Twelve checks, all measurable. Run at 390x844, 768x1024 and 1440x900 against the
fixture server, with a folder of at least 40 messages, and compare to
`C:/Users/abhay/Desktop/_mailer_shots/audit-before.json`.

1. **No crush.** At 390px with 40 rows, every `.msg` satisfies
   `getBoundingClientRect().height === scrollHeight` and `height >= 76`, and for
   every row `prev.getBoundingClientRect().bottom < msg.getBoundingClientRect().bottom`.
   Before: rows 44.00 against a natural 75, previews 30.75px inside the next row.
2. **Hairline placement.** Computed `.msg::after` `left` is 68px, the clear gap
   from preview ink to the rule is 8 to 11px, and the gap from the rule to the
   next sender's ink is at least 1.8 times that.
3. **Nothing parked off-screen.** `aside.rail` computes `display:none` at 390px;
   no element on `/mail` at 390px has `scrollWidth > clientWidth`; with
   `#accountSheet` open, Install app, Close mailbox and Sign out each return a
   rect fully inside the 390px viewport. Before: rail 843 against 390, three
   controls unreachable.
4. **Zero glyph icons.** No character above U+2000 appears in any rendered text
   node of `/mail` at any of the three widths. Before: 8 at 390, 15 at 1440.
5. **Icons do not eat taps.** `.ic` computes `pointer-events:none`, and
   `grep -n "e.target.getAttribute('data-act')" static/js/mail.js` returns
   nothing. A synthetic click dispatched on the `<svg>` inside every
   `[data-act]`, `.tab`, `.fold`, `.mrow` and `.msg` fires its handler.
6. **Tap targets.** Zero elements on `/mail` at 390px measure below 44x44 across
   `a, button, input, select, textarea, [role=button], .nav, .msg, .fold, .tab,
   .pib, .mrow`. Before: 9.
7. **Contrast.** Zero failures on `/mail` at all three widths against 4.5:1 body
   and 3:1 large or UI. Before: one at 1440, at 2.05:1.
8. **Back is one press.** From the list: open a message, back returns to the
   list at the same `#list.scrollTop`. Star the message, then back, still one
   press. Open compose over an open message: back lands on the reader, back
   again on the list, a third back leaves the app. Load `/mail#compose`, back
   lands on the list and does not reopen compose.
9. **Tab bar and safe area.** Exactly five `.tab` elements; `#tabbar` computes
   `position:fixed` with `bottom:0` and is a direct child of `<body>`;
   `getComputedStyle(list).paddingBottom >= tabbar.offsetHeight`; the last
   `.msg` bottom sits above `tabbar.getBoundingClientRect().top`; the viewport
   meta contains `viewport-fit=cover`.
10. **Reader pane.** With `body[data-pane="reader"]`, `#reader` computes
    `transform` as a matrix with no X translation and never the literal `none`,
    `#tabbar` is translated fully off, `#list` computes `overflow:hidden`, and
    `#reader` carries neither `inert` nor `visibility:hidden`. Closed, `#reader`
    is `visibility:hidden` and `inert`, and `document.documentElement.scrollWidth`
    is still 390.
11. **Real states.** During `loadMessages(true)` at 390px, `#list` contains
    `.skrow` elements and no `.spin`, each `.skrow` is within 2px of the real
    row height, and `#list` reports `aria-busy="true"` while loading and
    `"false"` after. Empty and error states each render an `.ic-32` sprite, one
    line of text and, for error, a Retry that issues a new request.
12. **Motion and console.** Under `prefers-reduced-motion: reduce`, `#reader`,
    `#tabbar`, `.bsheet .sheet` and `.backdrop` all report
    `transition-duration: 0s`; no rule in `mail.html` animates `width`,
    `height`, `top` or `left`; all three widths load with zero console errors
    and zero failed requests; and `sw.js` `VERSION` differs from `'jm-v1'`.

### 14.11 Hand-off, outside the two owned files

1. `static/sw.js`: bump `VERSION` from `'jm-v1'` in the same commit, or this
   ships broken to exactly the people who installed the app.
2. `templates/fragments/pwa.html`: unchanged. Its `black` status-bar style is
   correct and only means the top inset is 0 in the installed iOS app, which
   this layout already tolerates.
3. `SecurityConfig.java:110-115`: the policy is `default-src 'self'; style-src
   'self' 'unsafe-inline'`, so inline styles are legal, and `/logout` is a plain
   `<a href>` while Spring Security's default logout is POST-only when CSRF is
   on. Preserve the existing markup here and have whoever owns that file confirm
   the link is not silently returning 405.
4. `/app` keeps two contrast failures the audit found and this pass does not
   touch: `#verifyExportBtn` at 3.39:1 and `tbody#campaignBody td.num` at
   4.00:1.
5. Console nav rows are `<div class="nav-item" data-view="...">` with no button,
   role or tabindex, so they are unreachable by keyboard as well as parked
   off-screen at 390px (sidebar scrollWidth 2275 against 390, 15 rows
   unreachable). Section 11 is a separate pass and still owes it.

---

## 15. Stacking order

One table, because the failure it prevents is invisible in review and obvious on a
phone. A driven run found the install card at `z-index: 9998` sitting over the
mailbox account sheet at 150: taps meant for "Install app" and "Close mailbox"
landed on the card's own markup and did nothing, and five of the six folders in
the folder sheet were unreachable the same way. That is the exact regression the
rebuild existed to fix, arriving by a different route.

| Layer | z-index | What lives here |
|---|---|---|
| content | 0 to 9 | the list, the reader, panels |
| sticky chrome | 30 | sticky headers, table heads |
| scrim (console drawer) | 60 | |
| bottom tab bar | 60 | |
| console nav drawer | 70 | over its own scrim |
| install card | 100 | over the tab bar, under every dialog |
| sheet scrim | 140 | |
| sheets and modals | 150 | anything with `aria-modal="true"` |
| toasts | 200 | above everything, never interactive |

Rules that follow from it:

- **A promotion never outranks a surface the person deliberately opened.** The
  install card is a promotion. So are any future banners.
- Every dialog carries `aria-modal="true"`. `pwa.js` reads that attribute rather
  than any class name to know a dialog is open, because it is shared by four pages
  and must not know the markup of any of them.
- Every dialog closes on Escape. `pwa.js` relies on it: when the install card is
  opened from inside a sheet, it dispatches Escape and shows the card on the next
  frame, otherwise the card would paint underneath the sheet it was summoned from
  and the row would read as dead.
