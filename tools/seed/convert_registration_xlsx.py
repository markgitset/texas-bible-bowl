#!/usr/bin/env python3
"""Convert the 2026 Registration workbook into the app's seed JSON (item 17, F13).

Reads the event-year workbook (default ~/Downloads/Registration.xlsx) and writes a
SeedRequest JSON (default ~/Downloads/tbb-seed-2026.json) for `POST /admin/seed`.

    python3 tools/seed/convert_registration_xlsx.py [workbook.xlsx] [out.json]

The workbook and the JSON both contain attendee PII (including minors' names):
NEITHER may ever be committed — keep them outside the repo (hence the ~/Downloads
defaults). This script itself contains no data and is safe to commit.

Python stdlib only (an .xlsx is a zip of XML), so it runs anywhere without pip.

Mapping notes:
- `Attendees` is the master tab; `Congregations` supplies address/phone/code/site.
- Youth testers carry a school grade (the workbook has no birthdates); the server
  turns it into a graduation year and collects the real birthdate at first enrollment.
- A team is hosted by the congregation contributing the most members; members from
  another congregation (the 2026 combo teams) carry their own `congregationName`.
- Coach-typed rows become guests (coach *accounts* are not attendee rows in the app);
  their emails become pending coach grants consumed at signup.
- Volunteers are non-tester attendee rows carrying one or more Positions; they become guests
  (non-contestant participants) with those positions. The converter guards the "missing
  volunteers" gap three ways: it counts volunteers in the summary (a run that imported zero is
  obvious), warns on any Attendee Type token it doesn't recognize (a mistyped volunteer category),
  and warns if the workbook has an unread tab whose name looks volunteer-related (this script only
  reads the Attendees tab). A tester who ALSO volunteers keeps their contestant row and is warned:
  the seed's tester DTOs don't carry positions, so that volunteer role is recorded separately until
  the API-evolution phases let contestant participations hold positions.

Run `python3 tools/seed/convert_registration_xlsx_test.py` after changing this file (a stdlib-only
self-test over a synthetic workbook; not wired into Gradle/CI).
"""
import json
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
REL = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'

GRADES = {'3rd': 3, '4th': 4, '5th': 5, '6th': 6, '7th': 7, '8th': 8,
          'Freshman': 9, 'Sophmore': 10, 'Sophomore': 10, 'Junior': 11, 'Senior': 12}
SHIRTS = {'Youth Small': 'YS', 'Youth Medium': 'YM', 'Youth Large': 'YL',
          'Adult Small': 'AS', 'Adult Medium': 'AM', 'Adult Large': 'AL',
          'Adult XL': 'AXL', 'Adult 2XL': 'AXXL', 'Adult 3XL': 'AXXXL'}
GENDERS = {'Male': 'MALE', 'Female': 'FEMALE'}
PREFERENCES = {'E-Mail': 'EMAIL', 'Email': 'EMAIL', 'Phone': 'PHONE', 'Text': 'TEXT'}
# Ind Category code -> (division grade range, inexperienced)
IND_RANGES = {'E': range(3, 7), 'J': range(7, 10), 'S': range(10, 13)}
# Every Attendee Type token the row logic handles. A row typed anything else falls through to the
# guest branch (so it's still imported), but the token is surfaced so a mistyped or brand-new
# category — e.g. a volunteer entered as "Helper" — is caught rather than silently reclassified.
KNOWN_TYPES = {'Tester', 'Coach', 'Guest', 'Volunteer'}

warnings = []


def warn(msg):
    warnings.append(msg)


# --- minimal stdlib xlsx reading -------------------------------------------------

def load_workbook(path):
    z = zipfile.ZipFile(path)
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    rels = ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
    rel_map = {rel.get('Id'): rel.get('Target') for rel in rels}
    sheets = {}
    for s in wb.findall('.//m:sheet', NS):
        target = rel_map[s.get(REL)]
        sheets[s.get('name')] = target if target.startswith('xl/') else 'xl/' + target
    try:
        shared = [''.join(t.text or '' for t in si.findall('.//m:t', NS))
                  for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si', NS)]
    except KeyError:
        shared = []
    return z, sheets, shared


def sheet_rows(z, sheets, shared, name):
    def col_index(ref):
        n = 0
        for ch in re.match(r'([A-Z]+)', ref).group(1):
            n = n * 26 + ord(ch) - 64
        return n - 1

    out = []
    for row in ET.fromstring(z.read(sheets[name])).findall('.//m:row', NS):
        cells = {}
        for c in row.findall('m:c', NS):
            v = c.find('m:v', NS)
            if c.get('t') == 's' and v is not None:
                val = shared[int(v.text)]
            elif c.get('t') == 'inlineStr':
                val = ''.join(x.text or '' for x in c.findall('.//m:t', NS))
            else:
                val = v.text if v is not None else ''
            cells[col_index(c.get('r'))] = (val or '').strip()
        width = max(cells) + 1 if cells else 0
        out.append([cells.get(i, '') for i in range(width)])
    return out


# --- workbook-specific parsing ---------------------------------------------------

def site_id(site_name):
    return re.sub(r'[^a-z0-9]+', '-', site_name.lower()).strip('-') or None


def parse_address(raw):
    """Best-effort split of a one-line address into (street, city, state, zip)."""
    flat = ' '.join(raw.split())
    m = re.search(r'[,\s](TX|Texas)\.?,?\s+(\d{5})(?:-\d{4})?$', flat, re.IGNORECASE)
    state, zip_code = ('TX', m.group(2)) if m else ('', '')
    rest = flat[:m.start()].strip(' ,') if m else flat
    parts = [p.strip() for p in rest.split(',') if p.strip()]
    if len(parts) >= 2:
        return ', '.join(parts[:-1]), parts[-1], state, zip_code
    # No comma before the city ("5382 Texas Ave Abilene", "532 Comal Ave. New Braunfels"):
    # everything after the last street-suffix token is the city (handles multi-word cities).
    suffix = re.match(r'(?i)(.*\b(?:Ave|Avenue|St|Street|Rd|Road|Dr|Drive|Ln|Lane|Blvd|Pkwy|Hwy)\.?)\s+(\S.*)$', rest)
    if suffix and m:
        return suffix.group(1), suffix.group(2), state, zip_code
    words = rest.rsplit(' ', 1)
    if len(words) == 2 and m:
        return words[0], words[1], state, zip_code
    return rest, '', state, zip_code


def convert(workbook_path):
    z, sheets, shared = load_workbook(workbook_path)

    # Only the Attendees + Congregations tabs are read. If the workbook keeps volunteers on a
    # separate tab (as it does for housing/nametags/tribe-leader assignments), those people would
    # be invisible to this converter — flag any such tab so it isn't silently missed.
    for tab in sheets:
        low = tab.lower()
        if tab not in ('Attendees', 'Congregations') and ('volunteer' in low or 'position' in low):
            warn(f'workbook has an unread tab {tab!r} that looks volunteer-related — this converter '
                 f'only reads the Attendees tab; volunteers listed only there are NOT imported')

    congregations = {}  # trimmed lowercase name -> seed dict
    for r in sheet_rows(z, sheets, shared, 'Congregations')[2:]:
        if not r or not r[0].strip() or 'Gender' in r[:1]:
            continue
        name = r[0].strip()
        if name.lower() in congregations:
            continue  # summary section repeats below the data rows
        street, city, state, zip_code = parse_address(r[2] if len(r) > 2 else '')
        congregations[name.lower()] = {
            'name': name,
            'city': city,
            'state': state,
            'mailingAddress': street,
            'zip': zip_code,
            'phone': r[3].strip() if len(r) > 3 else '',
            'code': (r[5].strip() if len(r) > 5 else '')[:2].upper(),
            'siteId': site_id(r[1].strip()) if len(r) > 1 else None,
            'coachEmails': [],
            'teams': [],
            'unassigned': [],
            'individuals': [],
            'guests': [],
        }

    rows = sheet_rows(z, sheets, shared, 'Attendees')
    header = rows[2]
    col = {name: i for i, name in enumerate(header)}

    def cell(row, name):
        i = col.get(name)
        return row[i].strip() if i is not None and i < len(row) else ''

    teams = defaultdict(lambda: defaultdict(list))  # team name -> congregation -> members
    count = 0
    for r in rows[3:]:
        first, last = cell(r, 'First Name'), cell(r, 'Last Name')
        if not first and not last:
            continue
        count += 1
        full_name = f'{first} {last}'.strip()
        cong_name = cell(r, 'Congregation')
        cong = congregations.get(cong_name.lower())
        if cong is None:
            warn(f'{full_name}: unknown congregation {cong_name!r} — skipped')
            continue
        types = {t.strip() for t in cell(r, 'Attendee Type').split(',') if t.strip()}
        unknown_types = types - KNOWN_TYPES
        if unknown_types:
            warn(f'{full_name}: unrecognized Attendee Type {sorted(unknown_types)} — imported as a '
                 f'guest; if this is a volunteer category, add it to KNOWN_TYPES')
        gender = GENDERS.get(cell(r, 'Gender'))
        shirt = SHIRTS.get(cell(r, 't-shirt'))
        if cell(r, 't-shirt') and shirt is None:
            warn(f'{full_name}: unknown shirt size {cell(r, "t-shirt")!r}')
        tribe_leader = cell(r, 'Tribe Leader') == 'Yes'
        positions = [p.strip() for p in cell(r, 'Positions').split(',') if p.strip()]
        grade_txt = cell(r, 'School Grade')
        ind_cat = cell(r, 'Ind Category')

        # A tester who ALSO volunteers keeps their contestant row here, but the seed's tester DTOs
        # don't carry positions — so their volunteer role would be lost. Surface it (the person's
        # positions are event-ops data the registrar re-enters, until contestant participations
        # carry positions natively in the API-evolution phases).
        if 'Tester' in types and positions:
            warn(f'{full_name}: tester who also volunteers (positions {positions}) — the volunteer '
                 f'role is NOT seeded onto the contestant; record it separately')

        if 'Coach' in types:
            email = cell(r, 'email').lower()
            if email:
                cong['coachEmails'].append(email)
            else:
                warn(f'{full_name} ({cong_name}): coach without an email — grant by hand later')

        if 'Tester' in types and (grade_txt == 'Adult' or ind_cat.startswith('Adult')):
            if shirt is None:
                warn(f'{full_name}: adult tester without a shirt size — skipped')
                continue
            cong['individuals'].append({
                'name': full_name, 'gender': gender, 'shirtSize': shirt,
                'tribeLeaderWilling': tribe_leader,
            })
            continue

        if 'Tester' in types:
            grade = GRADES.get(grade_txt)
            if grade is None or shirt is None:
                warn(f'{full_name}: youth tester missing grade/shirt ({grade_txt!r}) — skipped')
                continue
            m = re.match(r'.*\[([EJS])([IE])\]$', ind_cat)
            if m:
                if grade not in IND_RANGES[m.group(1)]:
                    warn(f'{full_name}: grade {grade} vs Ind Category {ind_cat!r} mismatch — grade wins')
            elif ind_cat:
                warn(f'{full_name}: unrecognized Ind Category {ind_cat!r}')
            member = {
                'name': full_name, 'gender': gender, 'shirtSize': shirt, 'grade': grade,
                'inexperienced': bool(m) and m.group(2) == 'I',
            }
            team_name = cell(r, 'Team Name')
            if team_name:
                teams[team_name][cong_name.lower()].append(member)
            else:
                cong['unassigned'].append(member)
            continue

        # Everyone else (Guest and/or Coach without Tester) is a registered guest.
        contact = {
            'address': cell(r, 'street address'), 'city': cell(r, 'city'),
            'state': cell(r, 'state'), 'zip': cell(r, 'zip'),
            'phone': cell(r, 'phone'), 'email': cell(r, 'email'),
        }
        preference = PREFERENCES.get(cell(r, 'communication'))
        has_contact = any(contact.values()) or preference
        cong['guests'].append({
            'name': full_name, 'gender': gender, 'shirtSize': shirt,
            'positions': positions,
            'tribeLeaderWilling': tribe_leader,
            'contact': ({**contact, 'preference': preference} if has_contact else None),
        })

    # Teams: host = the congregation with the most members; others become visiting members.
    for team_name, by_cong in sorted(teams.items()):
        host_key = max(by_cong, key=lambda k: len(by_cong[k]))
        members = []
        for cong_key, ms in by_cong.items():
            for m in ms:
                if cong_key != host_key:
                    m = {**m, 'congregationName': congregations[cong_key]['name']}
                members.append(m)
        if len(members) > 4:
            warn(f'team {team_name!r} has {len(members)} members (max 4) — extras stay, fix by hand')
        congregations[host_key]['teams'].append({'name': team_name, 'members': members})

    for cong in congregations.values():
        cong['coachEmails'] = sorted(set(cong['coachEmails']))

    return {'seasonYear': '2026', 'congregations': list(congregations.values())}, count


def main():
    workbook = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / 'Downloads' / 'Registration.xlsx'
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path.home() / 'Downloads' / 'tbb-seed-2026.json'
    seed, attendee_count = convert(workbook)

    out.write_text(json.dumps(seed, indent=2))
    congs = seed['congregations']
    tally = Counter(
        members=sum(len(t['members']) for c in congs for t in c['teams']) + sum(len(c['unassigned']) for c in congs),
        teams=sum(len(c['teams']) for c in congs),
        individuals=sum(len(c['individuals']) for c in congs),
        guests=sum(len(c['guests']) for c in congs),
        # Volunteers are guests carrying at least one position — called out so a run that imported
        # zero (the gap this converter guards against) is obvious at a glance.
        volunteers=sum(1 for c in congs for g in c['guests'] if g['positions']),
        coachEmails=sum(len(c['coachEmails']) for c in congs),
    )
    print(f'read {attendee_count} attendee rows -> {len(congs)} congregations, {dict(tally)}')
    print(f'wrote {out} — contains PII, do NOT commit it')
    for w in warnings:
        print(f'WARN: {w}', file=sys.stderr)
    print(f"\nseed it with:\n  curl -sS -X POST <backend>/admin/seed -H 'Authorization: Bearer <admin JWT>' "
          f"-H 'Content-Type: application/json' --data @{out}")


if __name__ == '__main__':
    main()
