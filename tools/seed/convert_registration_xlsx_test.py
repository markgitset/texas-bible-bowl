#!/usr/bin/env python3
"""Self-test for convert_registration_xlsx.py — builds a tiny synthetic workbook (no PII, no
external deps) and asserts the converter's classification, especially that volunteers are
imported (the "missing-volunteers" gap the schema redesign guards against).

    python3 tools/seed/convert_registration_xlsx_test.py

Stdlib only, like the converter itself; not wired into Gradle/CI (the converter is a standalone
operator tool), so run it by hand after touching the converter.
"""
import sys
import zipfile
from pathlib import Path

import convert_registration_xlsx as conv


def _sheet_xml(rows):
    """Minimal worksheet XML with inline string cells (avoids a sharedStrings table)."""
    def col(i):
        s, i = '', i
        while True:
            s = chr(ord('A') + i % 26) + s
            i = i // 26 - 1
            if i < 0:
                return s
    out = ['<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    for r, cells in enumerate(rows, start=1):
        out.append(f'<row r="{r}">')
        for c, val in enumerate(cells):
            ref = f'{col(c)}{r}'
            esc = (val or '').replace('&', '&amp;').replace('<', '&lt;')
            out.append(f'<c r="{ref}" t="inlineStr"><is><t>{esc}</t></is></c>')
        out.append('</row>')
    out.append('</sheetData></worksheet>')
    return ''.join(out)


def _build_workbook(path, attendees, congregations, extra_tabs=()):
    tabs = [('Attendees', attendees), ('Congregations', congregations), *extra_tabs]
    wb_sheets = ''.join(
        f'<sheet name="{name}" sheetId="{i+1}" r:id="rId{i+1}"/>' for i, (name, _) in enumerate(tabs)
    )
    rels = ''.join(
        f'<Relationship Id="rId{i+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
        f'relationships/worksheet" Target="worksheets/sheet{i+1}.xml"/>' for i in range(len(tabs))
    )
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr('xl/workbook.xml',
                   '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                   f'<sheets>{wb_sheets}</sheets></workbook>')
        z.writestr('xl/_rels/workbook.xml.rels',
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/'
                   f'relationships">{rels}</Relationships>')
        for i, (_, rows) in enumerate(tabs):
            z.writestr(f'xl/worksheets/sheet{i+1}.xml', _sheet_xml(rows))


def run():
    tmp = Path('/tmp/_conv_selftest.xlsx')
    # Congregations tab: two header rows (skipped via [2:]), then one congregation.
    congregations = [
        ['Congregations'], ['Name', 'Site', 'Address', 'Phone', '', 'Code'],
        ['First Church', 'Bandina', '1 Main St, Austin, TX 78701', '512-555-0100', '', 'FC'],
    ]
    # Attendees tab: a title row, a blank row, a header row, then data.
    header = ['First Name', 'Last Name', 'Congregation', 'Attendee Type', 'Gender', 't-shirt',
              'School Grade', 'Ind Category', 'Team Name', 'Positions', 'Tribe Leader', 'email',
              'street address', 'city', 'state', 'zip', 'phone', 'communication']
    attendees = [
        ['2026 Attendees'], [], header,
        # A pure volunteer (Guest type) with two positions — must be imported with positions.
        ['Vera', 'Volunteer', 'First Church', 'Volunteer', 'Female', 'Adult Medium', '', '', '',
         'Kitchen Helper, Scorekeeper', 'No', '', '', '', '', '', '', ''],
        # A youth tester on a team.
        ['Timmy', 'Tester', 'First Church', 'Tester', 'Male', 'Youth Medium', '7th', 'Junior [JI]',
         'Team A', '', 'No', '', '', '', '', '', '', ''],
        # An adult tester who ALSO volunteers — surfaced by a warning, seeded as an individual.
        ['Andy', 'Adult', 'First Church', 'Tester, Volunteer', 'Male', 'Adult Large', 'Adult',
         'Adult', '', 'Setup Crew', 'Yes', '', '', '', '', '', '', ''],
        # A row typed with an unrecognized category — imported as a guest, but warned.
        ['Holly', 'Helper', 'First Church', 'Helper', 'Female', 'Adult Small', '', '', '',
         'Registration Desk', 'No', '', '', '', '', '', '', ''],
    ]
    _build_workbook(tmp, attendees, congregations,
                    extra_tabs=[('Volunteer Positions', [['ignored']])])

    conv.warnings.clear()
    seed, count = conv.convert(tmp)
    cong = seed['congregations'][0]
    guests = {g['name']: g for g in cong['guests']}

    # The pure volunteer is imported with her positions (the core "volunteers get imported" check).
    assert 'Vera Volunteer' in guests, 'pure volunteer was dropped!'
    assert guests['Vera Volunteer']['positions'] == ['Kitchen Helper', 'Scorekeeper'], \
        guests['Vera Volunteer']['positions']

    # The unrecognized-type row still lands as a guest with its position, plus a warning.
    assert 'Holly Helper' in guests, 'unrecognized-type attendee was dropped!'
    assert guests['Holly Helper']['positions'] == ['Registration Desk']
    assert any('unrecognized Attendee Type' in w and 'Holly' in w for w in conv.warnings), conv.warnings

    # The youth tester is a team member (not a guest); the adult tester is an individual.
    assert cong['teams'][0]['members'][0]['name'] == 'Timmy Tester'
    assert cong['individuals'][0]['name'] == 'Andy Adult'

    # The adult tester's volunteer role is surfaced (not silently lost), and the unread
    # volunteer-looking tab is flagged.
    assert any('tester who also volunteers' in w and 'Andy' in w for w in conv.warnings), conv.warnings
    assert any('unread tab' in w and 'Volunteer Positions' in w for w in conv.warnings), conv.warnings

    tmp.unlink()
    print(f'OK — {count} attendee rows; 2 guests ({sum(1 for g in guests.values() if g["positions"])} '
          f'volunteers), 1 team member, 1 individual; {len(conv.warnings)} warnings as expected')


if __name__ == '__main__':
    try:
        run()
    except AssertionError as e:
        print(f'FAIL: {e}', file=sys.stderr)
        sys.exit(1)
