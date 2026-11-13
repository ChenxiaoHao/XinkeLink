# XinkeLink Admin Frontend Phase 1 Implementation Plan

> **For agentic workers:** Implement this plan task by task in the current checkout. Do not commit or alter files outside `apps/admin` except this plan and the reused logo copy.

**Goal:** Build a screenshot-grounded administrator login, fixed admin shell, and demo Dashboard in the existing Vue app.

**Architecture:** Keep the existing Vue 3/Vite/Element Plus app and add Vue Router, Pinia, and Lucide Vue icons. Store only the local demo login state in Pinia/session storage, protect admin routes with a router guard, render unfinished menu routes as explicit development placeholders, and isolate sidebar menu and main content scrolling inside a viewport-sized shell.

**Tech Stack:** Vue 3, TypeScript, Vite, Element Plus, Vue Router, Pinia, lucide-vue-next, Vitest, Playwright.

**Spec:** User request and attached Stitch administration screenshots from 2026-10-23.

## Global Constraints

- Use `#6489B8` primary, `#F5F7FA` page background, and white cards.
- Do not display or persist any real administrator password.
- Keep all dashboard metrics labeled as local demo data and derive the displayed date dynamically.
- Do not claim live server, official identity, or real operational status.
- Do not implement non-dashboard business pages; use a clear “开发中” placeholder.
- Do not edit mini-program or Java business code, and do not run Git commit, push, reset, or clean.

## Review Focus

- A saved demo session redirects to the Dashboard; an absent session redirects protected routes to login.
- Empty or whitespace credentials fail validation; accepted local demo login has no hard-coded account/password.
- Logout clears the simulated session and returns to login.
- Body remains fixed while the main pane scrolls; topbar/sidebar stay fixed; a tall sidebar scrolls only inside its menu region.
- Dashboard dates and selected chart range render deterministically enough for tests, without implying live data.

## Tasks

### Task 1: Extend Existing App Dependencies and Tests

**Files:** Modify `apps/admin/package.json`, lockfile, and Vite/router bootstrapping; test in `apps/admin/tests`.

- [ ] Add Vue Router, Pinia, and Lucide Vue dependencies without replacing the existing project.
- [ ] Preserve the health boundary and its existing tests.
- [ ] Add failing tests for the new auth store and protected route behavior.
- [ ] Run the focused test to confirm it fails for the missing behavior.

### Task 2: Simulated Admin Login and Route Guard

**Files:** Create focused auth store, router, login view, and unit tests in `apps/admin/src` and `apps/admin/tests`.

- [ ] Implement local-only sign-in with form validation, password visibility, remember-account behavior, loading/failure states, and no credential literals.
- [ ] Guard `/admin/*` routes and route successful demo sign-in to `/admin/dashboard`.
- [ ] Verify login, return-to-login, and logout flows.

### Task 3: Fixed Admin Shell and Development Placeholders

**Files:** Create admin shell, sidebar, topbar, placeholder view and related tests.

- [ ] Render the Stitch navigation groups and selected-route states with Lucide line icons.
- [ ] Keep sidebar/footer/topbar fixed; constrain scrolling to the menu list and main content pane.
- [ ] Make all menu entries navigable; mark unfinished business routes clearly as “开发中”.

### Task 4: Dashboard Demo View

**Files:** Create Dashboard view, local demo data, trend chart, and focused tests.

- [ ] Match the screenshot hierarchy: greeting/date, four metrics, trend chart, pending items, and recent platform activity.
- [ ] Make time-range controls update the demo series and make business links navigate to matching placeholders.
- [ ] Keep all metrics visibly labeled as demo data and use a runtime date.

### Task 5: Browser and Build Verification

**Files:** Update `apps/admin/e2e` and capture screenshots under `.local`.

- [ ] Run all Vitest tests, Vue/TypeScript checking, and production build.
- [ ] Run Playwright flows for login/logout/guards/menu routing and fixed independent scrolling.
- [ ] Capture browser screenshots at 1366px, 1440px, and 1920px widths.
- [ ] Record actual browser verification and any remaining limits without claiming real-server integration.
