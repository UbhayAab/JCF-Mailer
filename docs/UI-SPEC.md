# Jarurat Mail / Campaign Studio - UI specification

The single contract every screen is built against.

Two kinds of sentence live in here and they settle disagreements in opposite
directions. Where a **design rule** and the code disagree, the code is wrong and
gets changed. Where a **statement of fact about what the code does** disagrees
with the code, this document is wrong and gets changed. Reading the second kind
as the first is how a correct feature gets deleted for not being in the spec, and
it has already happened here. Section 16 says how the two are told apart and how
a stale fact is meant to be caught.

Constraints that are not negotiable, because the box must render with zero
external requests and the app ships as one jar:

- No CSS framework, no icon font, no chart library, no CDN. Everything inlined
  or served from `/static`.
- The application's own chrome makes no external request: no remote font, no
  remote image, no remote script, in any template or stylesheet we ship. The
  Content-Security-Policy that enforces the surrounding boundary starts
  `default-src 'self'` but is longer than that and is **not** what this line
  implies. It is quoted verbatim in 14.11, which is the only place in this
  document that may state it. Read that before changing a header.
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

**The app chrome has one theme.** `style.css`, `mail.html`, `login.html`,
`landing.html` and `offline.html` carry no light palette and no
`prefers-color-scheme` block, and none is wanted.

That rule stops at the edge of the application's own markup, and the exception is
not a lapse. Two surfaces are separate documents holding HTML somebody else
wrote, and both are deliberately pinned light and both do carry
`prefers-color-scheme` handling:

- The reader frame, wrapped by `MailHtmlSanitizer.wrapDocument`. Pinning the
  ground alone was not enough. `color-scheme` sets what a document may render, not
  what the media query reports, so on a machine set to dark the sender's own
  dark-mode block still fired and painted white text onto our forced white paper.
  Measured at 1.00:1: the letter was one colour and read as an empty message.
  `rebuildRules` therefore drops a `prefers-color-scheme: dark` block and unwraps
  a light one, so the ground and the media query agree. `wrapDocument` also takes
  a `theme` of auto, light, dark or original, and only `auto` emits a dark block.
- The campaign preview frame, built by `previewDocument` in `console.js`, which
  repeats a `prefers-color-scheme: dark` rule that restates the light values for
  the same reason and is kept in step with `wrapDocument`.

Neither is a light theme for this application. Deleting either one restores the
blank-message defect, so a sweep for `prefers-color-scheme` must not treat those
four files as violations of this section.

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

One system, defined in `templates/fragments/icons.html`, which holds 95 `<symbol>`
elements at the time of writing. Used as:

```html
<svg class="ic" aria-hidden="true"><use href="#i-inbox"/></svg>
```

```css
.ic { width:18px; height:18px; flex:none; fill:none; stroke:currentColor;
      stroke-width:1.75; stroke-linecap:round; stroke-linejoin:round;
      pointer-events:none; }
.ic-lg { width:20px; height:20px }
.ic-sm { width:15px; height:15px; stroke-width:1.9 }
.ic-32 { width:32px; height:32px; stroke-width:1.5; color:var(--text-faint) }
```

`pointer-events:none` is in that base and is not cosmetic: without it the `<svg>`
becomes the click target inside every icon-only button and the delegated handlers
stop firing. 14.8 has the full account. `.ic-32` is the empty and error state
size from section 9.

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

**Built and shipped.** This section was written while none of it existed and it
still reads that way in places; the rules in it are live and the tense is not.
Section 14 is the resolved version and 14.10 is the acceptance list it was
signed off against.

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

**That row was broken and has been fixed.** The bottom border sat above the
preview line, so every preview read as if it belonged to the message below it.
Both mechanisms are described in 14.0 and the repair is in 14.4: `mail.html` now
carries `.msg::after` inset to `left:68px` rather than a full-bleed
`border-bottom`, and the padding is 16 top against 8 bottom. Do not reintroduce
`border-bottom` on `.msg` below 900px.

### Message row, desktop

Single line, 44px, three columns: sender 220px, subject and preview sharing the
rest with the preview in `--text-mute` after a middot, time right aligned.
Density is the point on a desktop; three-line rows waste the screen.

---

## 11. The phone console

The console is not the phone product, but it must not be broken there.

Below 900px the console rail becomes a **slide-in drawer** behind a hamburger
in the topbar, with a scrim. Not a horizontal scroller.

**Built.** `console.html` carries `header.mobile-topbar` with `#navToggle`
(`aria-controls="navDrawer"`, `aria-expanded`), the rail as
`aside.sidebar#navDrawer`, and `#navScrim`; `style.css` holds the geometry in its
`@media (max-width:900px)` block, drawer at z-index 70 over a scrim at 60. The
keyboard defect 14.11 recorded against the same rows is fixed too: every
`div.nav-item` now carries `role="button"` and `tabindex="0"`.

Tables keep the existing card treatment (`stampTableLabels`), which works.
Charts keep measuring `clientWidth` so one SVG unit is one CSS pixel.

---

## 12. Entry, install and sign-in

### The install surface

A phone visitor who is not signed in lands on a page whose primary action is
**Install**, not "Sign in". Android and Chromium fire `beforeinstallprompt` and
get a real button. iOS Safari gets the Share sheet steps. Anything else gets
the browser-menu wording. The card is built in `static/js/pwa.js`.

**Built.** `landing.html` leads with `button.lp-b.lp-install` reading "Install the
app" and repeats the offer lower down as `button.lp-row.lp-installrow`;
`login.html` carries `#siInstall` with its own `data-jm-install` button. `pwa.js`
delegates on `closest('[data-jm-install]')`, so every one of those works with no
per-page wiring and a new one needs only the attribute.

### One login

A person used to sign into the console with one password and then hit a second
prompt for the mailbox password, because Campaign Studio authenticates against
`app_user` and Stalwart authenticates against its own store. On a phone that was
two passwords before any mail appeared, and most people only have the second.

**Built.** `SecurityConfig.authenticationManager` puts `DaoAuthenticationProvider`
ahead of `MailboxAuthenticationProvider` on one `ProviderManager`;
`LoginLandingHandler` offers the accepted password to the mail server and then
picks the landing route. The rule below is what those enforce:

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

**Point 4 no longer describes the code, and the change was deliberate.**
`LoginLandingHandler.landingFor` decides in this order: an empty or
`MAIL_READ`-less set goes to `/app` (VIEWER holds no mail permission at all, and
sending a viewer to `/mail` hands them a 403 rather than a screen); a mail-only
set goes to `/mail`; **everyone else goes to `/app` on every device**, with
`?desktop=1` and an explicit mailbox request as the only overrides. Its own
comment gives the reason: signing in on a phone with the account that runs
Campaign Studio used to land on the inbox with the console reachable only by
knowing to add a query parameter, which reads as the sign-in having gone
somewhere unintended. The account decides now, not the device.

That is a real conflict with this section and with section 1's "The default on a
phone", not just a stale sentence, and this pass does not resolve it: which
surface a console account should get on a phone is a design decision and belongs
to whoever owns section 1. What is settled is the fact. Anyone changing either
side reads `LoginLandingHandler` first.

Point 3 is the security boundary and it does not move. A mailbox password buys
its own mailbox and nothing else - the same thing it already buys in any IMAP
client. It never buys the ability to send a campaign. `Role.MAILBOX` still holds
exactly `MAIL_READ` and `MAIL_SEND`, and `SecurityConfig.MAIL_ONLY_PATHS` is the
list such a session may reach.

### What the session does after that, which this section did not used to say

Two things were added to the session model after this section was written. Both
are on screen, both have their own files, and neither is described anywhere else
in this document, which is how a reader ends up believing sign-in is one form and
nothing more.

1. **The sign-in expires after eight hours of inactivity, and says so.**
   `static/js/session.js` with `templates/fragments/session.html` draws a
   countdown, warns a few minutes out with a way to stay, and states plainly when
   it is over rather than letting the next click fail. It reads `GET /api/session`
   (or `/api/mail/session` on a mail-only session, because the first answers 403
   there) and does arithmetic locally against the server's own clock, so it does
   not poll and cannot keep the session alive by watching it. Its scrim is
   z-index 140 and its dialog 150, per section 15.
2. **A device can stay signed in across sessions.** `device/DeviceTokenService`,
   `DeviceCookie`, `DeviceCredentialCipher` and `PersistentDeviceFilter` mint a
   rotating token that carries the key to a sealed copy of the mailbox password,
   so a phone comes back signed in with no prompt. Enrolment happens in the
   filter and not in `LoginLandingHandler`, because the handler offers the
   password to the mail server on a thread that outlives the redirect and the
   mailbox is usually not open yet when it runs. Sign out deletes the row, which
   destroys the only copy of that password. `/api/devices/**` is on
   `MAIL_ONLY_PATHS` deliberately: the person most likely to need to sign a lost
   phone out is exactly the one with no `app_user` row.

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

**Shipped.** Read this section as the record of a change that landed, not as work
outstanding. Two files changed at the time: `templates/mail.html` and
`static/js/mail.js`. Everything under "Hand-off" at the end was somebody else's
commit and blocked that release; 14.11 says where each of those stands now.

The mailbox has moved on since. The rules below still hold and the geometry is
still the shipped geometry, but the markup quoted in 14.2 is a snapshot of that
commit rather than the current file, and every place it has since drifted is
marked inline. Diff against `mail.html` before believing an id here.

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

**Drifted.** That query string is now `?v=device1`. The version marker moves on
every change that has to reach an installed phone, which is the whole point of it
(14.8, third item), so the literal value here will always be a snapshot. What is
binding is that the tag carries a `?v=` at all and that it is bumped in the same
commit as the file it names.

```html
<body>
<th:block th:replace="~{fragments/icons :: sprite}"></th:block>

<div class="app">

  <!-- display:none below 900px. Not removed, so no listener needs a null guard.

       DRIFTED: the rail now carries `.navgroup` headings, `#folders` rendered by
       renderFolders (the same markup that fills `#sheetFolders`), and a
       `#railDevices` row. There is no `#railUnread`: the folder rows carry their
       own counts, so the single Inbox badge below no longer exists anywhere.
       `#railAvatar`, `#railEmail`, `#railCompose`, `#railLock` and the
       `data-jm-install` row are unchanged. -->
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
           a pane that does not exist cannot slide.

           DRIFTED: .rbar now carries nine actions, not four. Back, then
           #rbarTitle, then #rbarWho (the sender, aria-hidden, revealed once the
           head has scrolled past), then reply, reply-all, forward, flag,
           archive, move, delete and more, ids #rReply #rReplyAll #rForward
           #rStar #rArchive #rMove #rDelete #rMore. Every one is reached through
           the single delegated [data-act] listener on #reader, so adding to that
           bar is markup and a branch, never a new listener. -->
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
    <!-- DRIFTED: cTo, cCc and cSubject are still those ids, but cBody is gone.
         The body is now #cEditor, a contenteditable with role="textbox", because
         bold has to mean bold in the letter that is sent. A search for cBody
         finds nothing and must not be read as the field having been lost. -->
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
  the pending state. `placeChrome` now moves four nodes on the same principle,
  not two: `#q` between `#qPhone` and `#qDesk`, `#btnSend` between `#sendHead`
  and `#sendFoot`, `#composeFrom` between `#fromPhone` and `#fromHead`, and
  `#fmtGroup` between `#fmtDock` and `#fmtDeck`. Anything else that has to exist
  in two places joins that list rather than being mirrored.
- The install rows keep `data-jm-install`; `pwa.js` already delegates on
  `closest('[data-jm-install]')` and needs no change.

### 14.3 Breakpoints, exactly

| Query | What changes |
|---|---|
| `@media (min-width:900px) and (max-width:1100px)` | The existing tablet block, **narrowed from `max-width:1100px`**. Narrowing is mandatory: `display:none` cannot be transformed, so an unnarrowed block kills the phone slide. The contents listed here as unchanged have since changed twice, and both are deliberate: `.folders{display:none}` is gone, because the rail carries folders now and it is on screen at every width this block covers, and `.btnback` is gone in favour of `.rbar .pib[data-act="back"]{display:inline-grid}`, because the reader bar carries the only Back button there is. |
| `@media (max-width:900px)` (the rail scroller) | **Delete the whole block.** It is the defect: `aside.rail` measures scrollWidth 843 against clientWidth 390 at 390px, and `scrollbar-width:none` plus `::-webkit-scrollbar{height:0}` hides the only hint that a tail exists. |
| `@media (max-width:899.98px)` (new, placed last) | The entire phone shell: `.rail{display:none}`, `.app{grid-template-columns:1fr}`, `.phead{display:flex}`, `.topbar{display:none}`, `.tabbar{display:grid}`, `.mail{display:block;position:relative;overflow:hidden}`, `.folders{display:none}`, `.list{display:block}` and the whole list geometry, the reader pane, the sheets, full-screen compose, the FAB tab, `.pib`, `.btn{min-height:44px}`, and **the 16px input rule lifted out of the 760 block** so an 850px tablet does not get a tab bar and iOS-zooming inputs at once. Use 899.98 and not 900, because `max-width:900px` and `min-width:900px` both match at exactly 900. |
| `@media (max-width:760px)` | Keep only the small-phone type lift and `.att`, `.fold`, `.pill`, `#toasts`. **Delete `.msg{min-height:44px;padding:13px 14px}`** (that line is the crush trigger) and **delete `.rmeta .avatar{display:none}`** (the reader avatar is now the colour chip and belongs there). |
| `@media (prefers-reduced-motion:reduce)` | `.reader`, `.tabbar`, `.backdrop`, `.sheet`, `.fab`, `.sk` and `.spin` all drop to `transition:none; animation:none`. |

Two more queries have been added to `mail.html` since, and they belong to the
same shell rather than being strays to be tidied away:

| Query | What changes |
|---|---|
| `@media (min-width:1101px)` | Both panes are on screen, so `.rbar-t` is hidden - the folder name is already in the topbar - and `.reader.nomsg .rbar` is hidden, because section 9 asks an empty state to carry the action that puts something here and seven greyed controls above a sentence carry none. Only here: below 1101px that bar holds the Back button. |
| `@media (min-width:1340px)` | `.msg .prev{display:block}`. The desktop row is one line and the preview is the part that goes first; it comes back only where there is room for it. |

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

`#tabUnread` is `hidden` at zero and shows `n > 99 ? '99+' : n`, fed by a reduce
over `S.folders` in `renderFolders`. It is the only consumer of that reduce now:
`#railUnread` was removed when the rail gained the folder list, because every
folder row carries its own count and a second Inbox badge above them said the
same number twice.

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

**Done.** Every row of the table below has landed; it is kept as the record of
what each glyph became, not as work outstanding. The audit counted 8 characters
above U+2000 on `/mail` at 390px and 15 at 1440px, and the target was zero.

The sprite has grown with the rest of the mailbox: `fragments/icons.html` holds
95 symbols now, not the 69 it held when this table was written. Check the file
before adding one, because the new arrivals cover most of what a new surface
wants - `i-bold` through `i-clear-format` for the composer toolbar, `i-schedule`,
`i-undo`, `i-snooze`, `i-signature`, `i-keyboard`, `i-select-all`,
`i-mark-unread`, `i-bcc`, `i-save`, `i-sliders`.

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

**2. The message body is an iframe, and it was a black hole for height.**
`mountBody` built a sandbox with no `allow-same-origin` and no `allow-scripts`,
so the frame could never report its content height and iOS Safari sized it to
that content while ignoring the CSS height. A long message then stretched the
reader pane instead of scrolling inside it, the internal scroll never engaged,
and `.rbar` scrolled away with nothing to bring it back. The first fix was a
wrapper that scrolls while the frame keeps its natural height:

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

The `referrerpolicy` and the property assignment of `srcdoc` are the security
boundary documented in `MailHtmlSanitizer` and neither moves. Mount the frame
after the slide finishes: a srcdoc frame first laid out inside a
`visibility:hidden` subtree sometimes never paints on WebKit. `transitionend`
does not fire when the element was already at its target and is absent entirely
under reduced motion, so pair it with a 260ms timeout and take whichever fires
first.

**The sandbox list in that block is out of date and copying it back breaks the
reader.** The wrapper alone left two nested scrollers on one pane, so the frame
was given the ability to report its own height after all. The shipped line is:

```js
  // allow-scripts is here for exactly one script: the height reporter the
  // sanitiser appends, which the frame's own CSP pins to a sha256 so nothing
  // else can run. allow-same-origin is deliberately still absent.
  frame.setAttribute('sandbox', 'allow-popups allow-popups-to-escape-sandbox allow-scripts');
  frame.setAttribute('scrolling', 'no');
```

What holds that safe is not the sandbox list on its own, so read all four parts
together before touching any of them:

- `MailHtmlSanitizer.HEIGHT_REPORTER` is the only script in the document, and the
  frame's own meta CSP carries `script-src '<sha256 of that exact string>'`. A
  script the sender wrote does not match the hash and does not run. Change one
  character of the reporter and its hash has to be recomputed in the same commit,
  or every message frame silently stops resizing.
- `allow-same-origin` is still absent, so the frame is on an opaque origin and
  cannot reach our cookies, our DOM or our API whatever runs in it.
- `listenForHeight` in `mail.js` establishes identity by comparing `event.source`
  against the frame's own `contentWindow`, never by origin: an opaque origin
  posts the string `"null"`, which is worth nothing as a check. The height is
  clamped to 60000px, or a message claiming to be 900,000px tall would build a
  scrollbar out of nothing. The listener removes itself once the frame is gone.
- Once the frame carries its own height, `.rwrap` gets `.sized` and stops being a
  scroller, which is the point: one pane, one scroller.

**3. The service worker will serve the old `mail.js` against the new
`mail.html`.** `sw.js` precaches `/js/mail.js` stale-while-revalidate and
deliberately never caches HTML. On the first launch after deploy an installed
phone therefore gets the new template with the previous script: `#tabbar` has no
wiring, `<body>` never gets `data-pane`, the reader never slides, and it reads
as a total deployment failure. Both ends are needed: bump `VERSION` in `sw.js`,
and bump the `?v=` on the script tag in `mail.html`, which changes the cache key
even if the worker itself has not updated yet.

The two literals this paragraph used to name have both moved on and will keep
moving, which is the point of them. `sw.js` is at `'jm-v6'` and the script tag is
at `?v=device1` at the time of writing. Neither number means anything; what is
binding is that both are bumped in the same commit as the file they cover.

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

This list is scoped to the commit described in section 14 and it has gone stale
four times. A "deliberately not built" entry is the most dangerous sentence in
this document, because a diligent reader who finds the feature anyway concludes
it was added by mistake and takes it out. Per section 16, every entry below now
names the file that would hold the thing, so the claim can be checked in one
command instead of believed.

**Four entries were wrong and have been removed. All four are built, wired and
shipped. Do not delete any of them.**

| Was listed as not built | Actually | Where it lives |
|---|---|---|
| Push notifications | Built | `static/js/notify.js` (browser half: ask, subscribe, stand the poll down), the `push/` package, and `sw.js` for rendering and clicking. Stalwart implements JMAP `PushSubscription` including the VAPID signing and the aes128gcm encryption, so this application is not in the delivery path and holds no key. Included by `mail.html` as `/js/notify.js?v=notify1`. |
| Multi-select with batch actions | Built | `static/js/mailbulk.js`, with its markup and script tag in `fragments/shortcuts.html`, which `mail.html` includes. Selection, a bar, long-press and shift-extend, and an inverse-operation undo. |
| Draft autosave | Built | `mail.js` `DRAFT`, `draftFields()`, `saveDraft()` and `resumeDraft()`, against `POST /api/mail/draft`, `POST /api/mail/draft/delete` and `GET /api/mail/draft` in `MailApiController`. A `sendBeacon` covers the close. |
| `visualViewport` keyboard choreography | Built | `mail.js` `dockToKeyboard()`, with `revealWritingArea()` and `keepCaretVisible()`, bound to `visualViewport` resize and scroll. It is what docks the format bar above the software keyboard. |

Two reasons this section gave for the absences were themselves wrong, and are
worth correcting so they are not repeated as fresh justifications:

- "There is no drafts endpoint" was false when written or shortly after. There
  are three, listed above. `MailApiController` also documents the one thing a
  client can get wrong with them: the id changes on every save, because JMAP
  makes an Email immutable, so a client that keeps the first id piles up copies
  in Drafts.
- "`/api/mail/move` and `/api/mail/delete` each take one id, so archive 40 is 40
  round trips" is still true of the endpoints, and it is now a documented cost
  rather than a reason for absence. `mailbulk.js` runs six requests in flight and
  puts the whole cost in one function, `each()`, so that when `MailService` and
  `MailApiController` learn to take a list the only thing that changes is that
  function's body. JMAP's `Email/set` takes a multi-key update map and would do
  all forty in one call.

**Still genuinely not built**, each with the file that would hold it:

| Not built | Where it would go | Why |
|---|---|---|
| Swipe to archive or delete | `mail.js`, alongside the existing swipe handler on `.reader` | Section 8 requires every destructive action to confirm, and a confirm sheet interrupting a gesture is worse than a button. Note that a horizontal swipe on the reader DOES exist and is not this: `swipeToNeighbour()` moves to the next or previous message, replaces rather than pushes history, and is ignored on a laptop. It is not a stray to be removed. |
| A custom edge-back gesture | `mail.js` | The platform gesture already works, because everything that means back calls `history.back()` (14.6). |
| Pull to refresh | `mail.js` | Needs a non-passive `touchmove` fighting native overscroll, and Refresh is already a labelled 44px control in the header. |
| Infinite scroll, virtualised list rendering | `mail.js` `renderList` and `.listfoot` | Load more already works and pages at 40. Nothing measured justifies replacing it. |
| Conversation threading | `MailService` and `mail.js` | Not started. `i-thread` exists in the sprite and is unused; a spare symbol is not a half-built feature. |
| A second floating FAB | `mail.html` | The Compose tab is the FAB (14.5). `.fab-in-pane` is Reply inside `.reader` and is the only other one. |
| A phone drawer for the mailbox rail | `mail.html` | The account sheet replaces it, which is 14.2's whole fix. The CONSOLE does have a drawer, per section 11, and that is a different rail in a different file. |
| Drag-to-dismiss on sheets | `mail.html` | Every sheet closes on Escape, on the scrim, on its close button and on back. |
| Offline or `sessionStorage` caching of mail | `sw.js`, which deliberately never caches HTML | The original reason, that the mailbox is password-gated per session, is now weaker than it was: a device token can carry a sealed mailbox password across sessions for months (section 12). The decision stands on its own merits, but do not repeat that sentence as though it were still the whole argument. |
| A light theme for the app chrome | `style.css` and `mail.html` | Section 2, which also says why the reader and preview frames are the exception and are not this. |
| A separate phone template | `templates/` | One `mail.html` at every width. Two templates is two of every fix. |

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
    and zero failed requests; and `sw.js` `VERSION` differs from whatever it held
    before the change under test. It held `'jm-v1'` when this list was written and
    holds `'jm-v6'` now, so the literal is worthless as a check; compare against
    the previous commit.

### 14.11 Hand-off, outside the two owned files

All five are closed. Kept as the record, with what each one turned out to be.

1. `static/sw.js`: `VERSION` was bumped and is bumped on every shipping change.
   Closed.
2. `templates/fragments/pwa.html`: unchanged, as intended. Its `black`
   status-bar style is correct and only means the top inset is 0 in the installed
   iOS app, which this layout already tolerates. Closed.
3. **The Content-Security-Policy. The policy quoted here was never the deployed
   one, and restoring it takes the console down.** See below. Closed, and the
   `/logout` half is closed too: `SecurityConfig` now answers the plain `<a href>`
   with `logoutRequestMatcher(new OrRequestMatcher(... GET "/logout", ... POST
   "/logout"))`, and its comment records that the link previously slid past
   `LogoutFilter` into a 404 rather than logging anybody out. Sign out is still
   a link in the rail, in the account sheet and in the console; keep it that way.
4. Both `/app` contrast failures are fixed. `#verifyExportBtn` is `.btn
   .btn-primary`, white on `--primary` `#2f6fed`, which measures **4.55:1**
   against the 4.5:1 body floor, was 3.39:1. `tbody#campaignBody td.num` inherits
   `--text-dim` `#b9b9b9` on `--panel`, **8.31:1**, and the failed-count cell that
   was the actual failure now paints `--danger-fg` `#f2776b` rather than
   `--danger` `#e0483c`, **5.92:1** against 4.00:1 before. Section 14.7's rule
   that `.mrow.danger` uses `--danger-fg` and never `--danger` is the same rule
   and it now holds in `console.js` too. Closed.
5. Console nav rows now carry `role="button"` and `tabindex="0"`, and the phone
   drawer is built. Section 11 has the detail. Closed.

#### The real Content-Security-Policy

Quoted rather than paraphrased, from `SecurityConfig.java:442-450`, because
paraphrasing it is what produced the entry above:

```java
.contentSecurityPolicy(csp -> csp.policyDirectives(
        // The composer previews arbitrary customer HTML in a sandboxed
        // iframe, so frame-src has to allow blob/data.
        "default-src 'self'; " +
        "img-src 'self' data: https:; " +
        "style-src 'self' 'unsafe-inline'; " +
        "script-src 'self' 'unsafe-inline'; " +
        "frame-src 'self' blob: data:; " +
        "object-src 'none'; base-uri 'self'; form-action 'self'"))
```

Three of those differ from what this document said, and each difference is
load-bearing:

- **`script-src 'self' 'unsafe-inline'`.** `console.html` carries **166 inline
  event handler attributes** - 134 `onclick`, 17 `onchange`, 15 `oninput`.
  Dropping `'unsafe-inline'` from `script-src` does not fail loudly: the page
  loads, and every one of those 166 controls silently does nothing. Tightening
  this is a real project that ports 166 handlers to delegated listeners first and
  changes the header last, in that order, never the other way round.
- **`img-src 'self' data: https:`.** Remote images in received mail and in
  campaign creatives are shown once the reader asks for them. Section 0's "no
  remote image" is about the application's own chrome, which loads none.
- **`frame-src 'self' blob: data:`.** The composer previews customer HTML in a
  sandboxed frame.

The nested documents have their own, much tighter policies and they are not this
one. `MailHtmlSanitizer` writes `default-src 'none'; img-src ...; style-src
'unsafe-inline'; font-src data:; script-src '<sha256>'; object-src 'none';
frame-src 'none'; form-action 'none'; base-uri 'none'` into the reader frame's
`<meta>`. That single hashed `script-src` is the height reporter and nothing
else; 14.8 has the account.

The header block at the top of this document is written from the point of view
of the box rendering with zero external requests, which is still true and is
still the constraint. It is not a quotation of the header, and it was read as one.

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
| content | 0 to 9 | the list, the reader, panels, `.fab-in-pane` at 5 |
| sticky chrome | 30 | sticky headers, table heads, the bulk bar `.jmb-wrap` |
| console sidebar, desktop | 40 | `aside.sidebar`, fixed against the scrolling main |
| console phone topbar | 45 | sticky, under the drawer it opens |
| scrim (console drawer) | 60 | |
| bottom tab bar | 60 | |
| console nav drawer | 70 | over its own scrim |
| install card, notification permission card | 100 | over the tab bar, under every dialog |
| session scrim | 140 | |
| sheet scrim | 140 | |
| sheets and modals | 150 | anything with `aria-modal="true"` |
| popover from inside a sheet | 160 | the send-later menu, the bulk move menu |
| update bar | 190 | see below |
| toasts | 200 | above everything, never interactive |

Four of those rows are additions made after the table was first written, and each
one is recorded here rather than left as an unexplained number in a stylesheet:

- **40 and 45** are the console shell, which predates the table and was simply
  missing from it.
- **160, a popover opened from inside a sheet.** The first consumer is the send
  later menu, which opens from a button inside the compose sheet at 150; a
  popover its own sheet paints over is a popover nobody can use. It stays under
  toasts because a toast must never be covered and is never interactive. Both
  `style.css` and `fragments/shortcuts.html` carry the same value and say so.
- **190, the update bar** from `static/js/update.js`, which is the one entry that
  sits above sheets. It is not an exception to the promotion rule below, because
  it does not rely on stacking to behave: it tests for a visible
  `[aria-modal="true"]` and stays down while one is open. A layer that outranks a
  dialog and defers to it by measurement is the pattern to copy if another
  ever needs to sit there.

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

---

## 16. How this document is kept honest

This section exists because the drift is the defect, not the individual stale
sentence. Correcting the sentences once is worth doing and does nothing to stop
the next round.

The failure has a shape and it has now happened at least six times in here. A
sentence describing what the code did on the day it was written stays in the
present tense; the code moves; a diligent reader arrives, believes the sentence,
and either deletes a working feature for not being in the spec or restores a
setting the spec describes and takes a screen down. Section 14.9 alone claimed
four shipped features were deliberately absent. Section 14.11 quoted a
Content-Security-Policy that has never been the deployed one, at line numbers
that have never held it, and 166 controls in `console.html` depend on the
difference.

### The two kinds of sentence

Every line in here is one of two things, and the opening of this document says
which way each settles a disagreement:

- **A design rule.** "Tap targets are 44x44 minimum below 760px." Timeless,
  argued from a reason, and binding on the code. If the code disagrees, the code
  is wrong.
- **A statement of fact about the code.** "The card is already built in
  `pwa.js`." "There is no drafts endpoint." "`sw.js` VERSION is `'jm-v1'`." True
  on a date, ages without warning, and binding on nobody. If the code disagrees,
  **this document is wrong** and gets corrected, and the feature stays.

Prefer the first. A rule that has to name a file, a line number, a version
string or a count is a rule with an expiry date on it, and it should say so.

### Two rules that make a stale fact catchable

1. **A claim that something is deliberately absent must name the file that would
   contain it.** "Push notifications: not built" cannot be checked without
   reading the whole tree, so nobody checks it and it rots in place. "Push
   notifications: would live in `static/js/notify.js`" is one `ls` away from
   being disproved. 14.9 is written this way now. An entry that cannot name its
   file is not yet a decision; it is an opinion about a feature nobody has
   located.
2. **Any statement about security policy quotes the code; it never paraphrases
   it.** Headers, sandbox attributes, CSP directives, permission sets, session
   scope. Paste the lines and name the file, as 14.11 does, so a reader can see a
   difference rather than having to reconstruct one from prose. A paraphrase of a
   header reads exactly like the header and is the one kind of error that gets
   "fixed" back into the code by somebody being careful. If a policy is stated in
   more than one place in this document, one of those places is going to be
   wrong: state it once and cross-reference it everywhere else.

### Three habits that follow

- **Mark the shipped.** A section describing work that has landed says so at the
  top, in bold, before the reader is thirty lines into a plan. Sections 10, 11,
  12, 14 and 14.7 carry that line now.
- **Keep the "before" numbers, retire the "current" ones.** 14.0's measured row
  heights and 14.10's before-figures are the evidence for the design and stay
  exactly as they are. A count of what exists today - 69 symbols, 8 glyphs,
  `'jm-v1'` - is a different thing, and it either carries "at the time of
  writing" or it does not belong in a specification.
- **When a claim here turns out to be wrong, fix the claim in the same commit
  that discovers it.** The four features in 14.9 were each found, doubted and
  left alone by somebody, more than once, and the document was never touched. A
  fact nobody corrects is a trap that has already been armed twice.
