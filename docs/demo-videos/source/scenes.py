"""Scene timelines for the PassportEmu + Precheck videos.

Every panel shows a raw recording from ../recordings. Times are in seconds:
`seg(clip, in, out, rate)` plays a source range, `hold(clip, t, dur)` holds a
real recorded frame. Layout `vp` is the visible source rectangle (x, y, w, h)
in the 1080 × 2340 recording; `h` is its displayed height before rotation.
Tap cues name the source time at which the recorded UI responds to the tap.
"""

TARGET = {'demo': 75.0, 'tutorial': 180.0}
PREVIEW = {'capture', 'confirm', 'start', 'read', 'outcome'}

FULL = (0, 0, 1080, 2340)
PH, CY = 864, 536
DEFAULT_LAYOUT = dict(cx=960, cy=CY, h=PH, vp=FULL, rot=0.0, dim=0.0, active=0.0, alpha=1.0, label=1.0)

# Layouts. PassportEmu is always left of Precheck when both are shown.
LEFT = dict(cx=580, vp=FULL, h=PH, rot=0.0)
RIGHT = dict(cx=1340, vp=FULL, h=PH)
SOLO_TOP = dict(cx=960, vp=(0, 130, 1080, 1420), h=PH)        # card, sample/edit actions, list rows
SOLO_BOTTOM = dict(cx=960, vp=(0, 970, 1080, 1370), h=PH)     # bottom action buttons
PAGE = dict(cx=520, vp=(10, 490, 1060, 1500), h=840, rot=90.0)  # photo page as the camera sees it
PRE_FULL = dict(cx=1440, vp=FULL, h=PH)
PRE_TOP = dict(cx=1440, vp=(0, 150, 1080, 1500), h=PH)        # capture button, image and form fields
READ_S = dict(cx=574, vp=(0, 1020, 1080, 1090), h=820, rot=0.0)  # PassportEmu milestones
READ_P = dict(cx=1425, vp=(0, 170, 1080, 1360), h=820)         # Precheck progress and dialog
RESULT_S = dict(cx=580, vp=(0, 140, 1080, 1700), h=PH)
OFF_RIGHT = dict(cx=2250, alpha=0.0)

# Source rectangles (x0, y0, x1, y1) used by callouts.
PAGE_CARD = (22, 502, 1058, 1974)
PAGE_NUMBER = (772, 1252, 848, 1538)
PAGE_EXPIRY = (352, 992, 422, 1318)
PAGE_DOB = (460, 1182, 532, 1508)
PRE_IMAGE = (30, 655, 1050, 1360)
PRE_NUMBER = (46, 798, 1035, 944)
PRE_EXPIRY = (46, 1017, 1035, 1166)

# Recorded tap targets: (clip, time the UI responds, x, y).
T_SAMPLE = ('smart-create', 5.166, 540, 1331)
T_EDIT = ('smart-create', 11.342, 284, 1209)
T_SURNAME = ('smart-create', 16.417, 540, 685)
T_DONE = ('smart-create', 24.956, 540, 2169)
T_USE = ('smart-show', 4.491, 540, 2099)
T_SHOW = ('smart-show', 9.908, 540, 2003)
T_NEXT_HOLD = ('smart-hold', 4.255, 540, 2168)
T_TAKE = ('precheck-capture', 7.348, 540, 928)
T_NEXT_DETAILS = ('precheck-details', 4.583, 986, 2190)
T_NEXT_READY = ('precheck-ready', 4.394, 986, 2190)
T_START = ('precheck-read', 6.260, 540, 1985)
T_TRY = ('smart-presets', 4.572, 303, 1360)
T_ACCENTED = ('smart-preset-select', 4.397, 540, 658)
T_HISTORY = ('smart-history', 4.312, 738, 223)
T_HELP = ('smart-help', 4.410, 870, 223)
T_NOTHING = ('smart-help', 10.878, 540, 620)

# Precheck's read take runs this much later than PassportEmu's: Precheck's
# first progress (passport detected) lines up with "Found the other phone".
READ_OFFSET = 5.9

CAPTURE = 'In Precheck, capture the whole displayed page.'
CONFIRM = 'Confirm the passport number and expiry.'
DOB = 'Use the same birth date in the Precheck check.'
NEXT_HOLD = 'In PassportEmu, tap Next: hold phones together.'
START = 'In Precheck, tap START READING.'
ALIGN = 'Hold the phones back to back and keep still.'
OUTCOME = 'Compare the captured details.'
AGAIN = 'Capture the new specimen again before reading.'
HELP = 'Check NFC, alignment and matching details.'


def seg(clip, a, b, rate=1.0):
    return dict(clip=clip, src_in=a, src_out=b, rate=rate, duration=(b - a) / rate)


def hold(clip, t, dur):
    return dict(clip=clip, src_in=t, src_out=t, rate=0, duration=dur)


def move(t, dur, **to):
    return dict(t=t, dur=dur, to=to)


def panel(pid, clips, base, *moves):
    return dict(id=pid, clips=clips, base=base, moves=list(moves))


def tap(pid, target):
    clip, change, x, y = target
    return dict(type='tap', panel=pid, clip=clip, change=change, x=x, y=y)


def box(pid, rect, t0, t1):
    return dict(type='box', panel=pid, rect=rect, t0=t0, t1=t1)


def link(a, b, t0, t1):
    return dict(type='link', a=a, b=b, t0=t0, t1=t1)


def nfc(t0, t1, cx=960, cy=520):
    return dict(type='nfc', t0=t0, t1=t1, cx=cx, cy=cy)


def cap(t0, t1, text):
    return dict(t0=t0, t1=t1, text=text)


def scene(sid, duration, purpose, layout, panels, captions=(), graphics=(), **extra):
    return dict(id=sid, duration=duration, purpose=purpose, layout=layout, panels=panels,
                captions=list(captions), graphics=list(graphics), **extra)


# --------------------------------------------------------------------- demo

def demo():
    return [
        scene('setup', 4.5, 'Establish both apps on their starting screens', 'paired full screens', [
            panel('smart', [seg('smart-home', 0, 2.07)], dict(LEFT)),
            panel('precheck', [hold('precheck-capture', 0.6, 4.5)], dict(RIGHT)),
        ], [cap(0.6, 4.3, 'Enable NFC on both phones.')], fade_in=0.5),

        scene('create', 10.5, 'Load sample details and change Doe to Smith', 'PassportEmu close-up', [
            panel('smart', [seg('smart-create', 3.4, 6.3), seg('smart-create', 10.5, 12.6),
                            seg('smart-create', 15.6, 21.0, 1.6), seg('smart-create', 24.4, 26.5)],
                  'continue', move(0, 0.8, **SOLO_TOP, active=1.0),
                  move(7.2, 0.7, **SOLO_BOTTOM), move(8.95, 0.7, **SOLO_TOP)),
            panel('precheck', [hold('precheck-capture', 0.6, 10.5)], 'continue',
                  move(0, 0.7, **OFF_RIGHT)),
        ], [cap(0.5, 10.2, 'Load sample details, then edit the holder.')],
            [tap('smart', T_SAMPLE), tap('smart', T_EDIT), tap('smart', T_SURNAME), tap('smart', T_DONE)]),

        scene('capture', 14, 'Show the photo page and capture it in Precheck', 'PassportEmu close-up, then paired', [
            panel('smart', [seg('smart-show', 2.6, 5.4), seg('smart-show', 8.9, 10.8), hold('smart-page', 3.0, 9.3)],
                  'continue', move(0.2, 0.7, **SOLO_BOTTOM),
                  move(4.5, 1.0, **PAGE, active=0.0)),
            panel('precheck', [hold('precheck-capture', 0.6, 1.4), seg('precheck-capture', 0.6, 8.55)],
                  dict(PRE_TOP, cx=2250, alpha=0.0),
                  move(4.6, 1.0, cx=PRE_TOP['cx'], alpha=1.0), move(5.6, 0.4, active=1.0)),
        ], [cap(0.3, 4.4, 'Tap Use this passport, then Show photo page.'), cap(5.4, 13.7, CAPTURE)],
            [tap('smart', T_USE), tap('smart', T_SHOW), tap('precheck', T_TAKE),
             box('precheck', PRE_IMAGE, 8.4, 13.7), link(('smart', PAGE_CARD), ('precheck', PRE_IMAGE), 8.4, 13.7)]),

        scene('confirm', 8, 'Match the specimen number and expiry in Precheck', 'paired, Precheck form close-up', [
            panel('smart', [hold('smart-page', 3.0, 8)], 'continue'),
            panel('precheck', [seg('precheck-details', 3.15, 4.55), hold('precheck-ready', 0.3, 2.0),
                               seg('precheck-ready', 0.3, 6.3)],
                  'continue', move(0, 0.6, **PRE_FULL), move(1.6, 0.7, **PRE_TOP),
                  move(6.35, 0.6, **PRE_FULL)),
        ], [cap(1.7, 6.3, CONFIRM)],
            [tap('precheck', T_NEXT_DETAILS), tap('precheck', T_NEXT_READY),
             box('smart', PAGE_NUMBER, 2.4, 6.2), box('precheck', PRE_NUMBER, 2.4, 6.2),
             link(('smart', PAGE_NUMBER), ('precheck', PRE_NUMBER), 2.4, 6.2),
             box('smart', PAGE_EXPIRY, 3.2, 6.2), box('precheck', PRE_EXPIRY, 3.2, 6.2),
             link(('smart', PAGE_EXPIRY), ('precheck', PRE_EXPIRY), 3.2, 6.2)]),

        scene('start', 11, 'Prepare PassportEmu, start Precheck, then align the phones', 'paired full screens', [
            panel('smart', [hold('smart-page', 3.0, 0.8), seg('smart-hold', 1.6, 7.4),
                            seg('smart-read', 0.0, 4.4)],
                  'continue', move(0, 0.8, **LEFT), move(0.8, 0.4, active=1.0), move(4.0, 0.4, active=0.0)),
            panel('precheck', [seg('precheck-ready', 4.9, 7.3), seg('precheck-read', 1.7, 10.3)],
                  'continue', move(0, 0.8, **RIGHT, active=0.0), move(4.1, 0.4, active=1.0),
                  move(7.4, 0.4, active=0.0)),
        ], [cap(0.9, 3.9, NEXT_HOLD), cap(4.2, 7.3, START), cap(7.6, 10.8, ALIGN)],
            [tap('smart', T_NEXT_HOLD), tap('precheck', T_START), nfc(7.5, 10.9)]),

        scene('read', 11, 'Both apps report progress through to completion', 'paired progress close-ups', [
            panel('smart', [seg('smart-read', 4.4, 12.9, 1.6), seg('smart-read', 12.9, 16.65)],
                  'continue', move(0, 0.8, **READ_S), move(7.7, 0.7, **LEFT)),
            panel('precheck', [seg('precheck-read', 10.3, 18.8, 1.6), seg('precheck-read', 18.8, 21.48)],
                  'continue', move(0, 0.8, **READ_P), move(7.7, 0.7, **RIGHT)),
        ], [cap(0.6, 7.4, 'Keep still until both apps finish.')]),

        scene('outcome', 8, 'Capture Finished in Precheck and the expected details in PassportEmu', 'paired', [
            panel('smart', [hold('smart-read', 16.6, 1.8), seg('smart-result', 0.2, 3.17)],
                  'continue', move(0, 0.7, **RESULT_S), move(7.4, 0.6, alpha=0.0)),
            panel('precheck', [hold('precheck-read', 21.45, 8)], 'continue', move(0, 0.7, **READ_P),
                  move(7.4, 0.6, alpha=0.0)),
        ], [cap(0.6, 7.3, OUTCOME)]),

        scene('another', 8, 'Load another ready-made specimen', 'PassportEmu close-up', [
            panel('smart', [seg('smart-presets', 2.4, 6.4), seg('smart-preset-select', 3.5, 7.5)],
                  dict(SOLO_TOP, cx=580, alpha=0.0), move(0, 0.6, **SOLO_TOP, alpha=1.0, active=1.0)),
        ], [cap(0.4, 4.3, 'Tap Try another for a different specimen.'), cap(4.9, 7.6, AGAIN)],
            [tap('smart', T_TRY), tap('smart', T_ACCENTED)], fade_out=0.6),
    ]


# ----------------------------------------------------------------- tutorial

def tutorial():
    return []
