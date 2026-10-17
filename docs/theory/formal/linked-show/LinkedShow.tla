------------------------------- MODULE LinkedShow -------------------------------
(***************************************************************************)
(* A linked slide show (ADR-0048 §8; the prose proof is docs/theory/11).   *)
(*                                                                         *)
(* Two windows show the same deck. Main orders every move: it numbers it   *)
(* (seq), acknowledges it to the window that sent it, and pushes it to the *)
(* other window. Each window keeps what it last exchanged with main        *)
(* (known), the moves it has sent that main has not acknowledged yet       *)
(* (out), and a push that arrived while its own move was on the way        *)
(* (held).                                                                 *)
(*                                                                         *)
(* The model follows the implementation's split of a window into three    *)
(* parts, because the races live between them:                             *)
(*   - the ENGINE (renderer.pdf): the page it shows. A key moves it at     *)
(*     once; it REPORTS its page later (once per animation frame), and it  *)
(*     reports after every [:show p] command even when nothing changed;    *)
(*   - app-db: the MIRROR of the last report, the blank colour, and the    *)
(*     link state;                                                         *)
(*   - the INTERCEPTOR (vinary.app.slides-link/outgoing), run after a      *)
(*     report or a local blank change, never after an incoming message:    *)
(*     it sends the window's current position when it differs from known.  *)
(*                                                                         *)
(* A position is a page and a blank colour. A window that hides pages (the *)
(* note pages of a deck) SNAPS a hidden page to a page it shows. Each      *)
(* window learns its hidden pages once, at a moment of its own (Rehide):   *)
(* before the show, or later, when a background scan or a refresh ends --  *)
(* so for a while the two windows may disagree about which pages exist.    *)
(* The switches named No.../AnnounceOnMount remove one rule each: they are *)
(* the negative controls, and every one of them must break a property.     *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
    Presenter, Audience,   \* the two member windows (model values)
    Nil,                   \* "nothing" (a model value)
    Pages,                 \* page indices, e.g. 0..1
    Blanks,                \* blank colours, e.g. {"none", "black"}
    NoBlank,               \* the colour that means "not blanked"
    MaxUser,               \* how many things the people do in total (bounds the state space)
    Reorder,               \* TRUE: any message in a channel may arrive next; FALSE: first in, first out
    SnapPresenter,         \* the presenter's snap once it knows its hidden pages, [Pages -> Pages]
    SnapAudience,          \* the audience's snap once it knows its hidden pages, [Pages -> Pages]
    NoHold,                \* control: show a push even while my own move is on the way
    NoHeldCheck,           \* control: show a held push without comparing it to my last acknowledged move
    NoApplying,            \* control: a blank sent while a push is being shown uses the stale mirror page
    NoStaleGuard,          \* control: show a push even when it is not newer than what I have seen
    NoMidGuard,            \* control: main accepts a move older than one it already accepted from that window
    AnnounceOnMount        \* control: the audience announces its start page as a move when its deck mounts

ASSUME Presenter # Audience
ASSUME NoBlank \in Blanks
ASSUME 0 \in Pages /\ Pages \subseteq Nat
ASSUME MaxUser \in Nat
\* a snap lands on a page the window shows, and a page the window shows stays where it is
ASSUME \A S \in {SnapPresenter, SnapAudience} : S \in [Pages -> Pages] /\ \A p \in Pages : S[S[p]] = S[p]

Win         == {Presenter, Audience}
Other(w)    == IF w = Presenter THEN Audience ELSE Presenter
Pos         == [page : Pages, blank : Blanks]
P0          == [page |-> 0, blank |-> NoBlank]
SnapOf(w)   == IF w = Presenter THEN SnapPresenter ELSE SnapAudience
Max(a, b)   == IF a >= b THEN a ELSE b
MaxPage     == CHOOSE p \in Pages : \A q \in Pages : q <= p

\* the snaps the configurations use
SnapNone      == [p \in Pages |-> p]                                         \* hides nothing
SnapLast      == [p \in Pages |-> IF p = MaxPage /\ p > 0 THEN p - 1 ELSE p]  \* hides the last page: shows the one before
SnapHide1Down == [p \in Pages |-> IF p = 1 THEN 0 ELSE p]                    \* hides page 1: shows page 0
SnapHide1Up   == [p \in Pages |-> IF p = 1 /\ 2 \in Pages THEN 2 ELSE p]      \* hides page 1: shows page 2 (UPWARD: rejected)

\* a window message has one shape, so TLC compares like with like: an ack carries a dummy pos, a push a dummy mid
Ack(s, mid, acc) == [type |-> "ack",  seq |-> s, mid |-> mid, accepted |-> acc,   pos |-> P0]
Push(s, p)       == [type |-> "push", seq |-> s, mid |-> 0,   accepted |-> FALSE, pos |-> p]

\* the indices of a channel that may be delivered next
Deliverable(ch) == IF ch = <<>> THEN {} ELSE IF Reorder THEN DOMAIN ch ELSE {1}
Remove(ch, i)   == [j \in 1..(Len(ch) - 1) |-> IF j < i THEN ch[j] ELSE ch[j + 1]]

VARIABLES
    mseq, mpos, lastMid,          \* main: the session's seq and position; the highest mid accepted per window
    phase, start,                 \* window: "joining" | "live"; the audience's start {seq, pos} while joining
    engine, pending, mirror,      \* window: the engine's page; a report is due; the last report's page
    blank, applying,              \* window: the blank colour; the page of a push being shown, until its report
    known, seq, ownSeq,           \* window: last exchanged position; newest seq seen; newest seq of my own move
    out, held, nextMid,           \* window: unacknowledged mids; the held push; the next mid
    snapped,                      \* window: it knows its hidden pages (Snap is in force)
    toMain, toWin,                \* channels, one per window and direction
    users, pcause,                \* bound: things done so far; ghost: why the engine may differ from known
    lastUserPos, echo, nonUser    \* ghosts: the last person-made move main accepted; a move sent without a cause;
                                  \*         how many accepted moves no person made (a snap's corrections)

vars == << mseq, mpos, lastMid, phase, start, engine, pending, mirror, blank, applying, known, seq, ownSeq,
           out, held, nextMid, snapped, toMain, toWin, users, pcause, lastUserPos, echo, nonUser >>

\* the page window w shows for page p: its hidden pages snap once it knows them
Snap(w, p) == IF snapped[w] THEN SnapOf(w)[p] ELSE p

TypeOK ==
    /\ mseq \in Nat /\ mpos \in Pos /\ lastMid \in [Win -> Nat]
    /\ phase \in [Win -> {"joining", "live"}]
    /\ start \in [seq : Nat, pos : Pos]
    /\ engine \in [Win -> Pages] /\ pending \in [Win -> BOOLEAN]
    /\ mirror \in [Win -> Pages \cup {Nil}]
    /\ blank \in [Win -> Blanks] /\ applying \in [Win -> Pages \cup {Nil}]
    /\ known \in [Win -> Pos \cup {Nil}]
    /\ seq \in [Win -> Nat] /\ ownSeq \in [Win -> Nat]
    /\ out \in [Win -> SUBSET Nat]
    /\ held \in [Win -> [seq : Nat, pos : Pos] \cup {Nil}]
    /\ nextMid \in [Win -> Nat]
    /\ snapped \in [Win -> BOOLEAN]
    /\ users \in Nat /\ pcause \in [Win -> {"none", "user", "snap", "announce"}]
    /\ lastUserPos \in Pos /\ echo \in BOOLEAN /\ nonUser \in Nat

Init ==
    /\ mseq = 0 /\ mpos = P0 /\ lastMid = [w \in Win |-> 0]
    \* main has claimed the audience window and sent it vv:open-files {link: {seq 0, P0}}: it is joining
    /\ phase = [w \in Win |-> IF w = Presenter THEN "live" ELSE "joining"]
    /\ start = [seq |-> 0, pos |-> P0]
    /\ engine = [w \in Win |-> 0] /\ pending = [w \in Win |-> FALSE]
    /\ mirror = [w \in Win |-> IF w = Presenter THEN 0 ELSE Nil]
    /\ blank = [w \in Win |-> NoBlank] /\ applying = [w \in Win |-> Nil]
    /\ known = [w \in Win |-> IF w = Presenter THEN P0 ELSE Nil]
    /\ seq = [w \in Win |-> 0] /\ ownSeq = [w \in Win |-> 0]
    /\ out = [w \in Win |-> {}] /\ held = [w \in Win |-> Nil] /\ nextMid = [w \in Win |-> 1]
    /\ snapped = [w \in Win |-> FALSE]
    /\ toMain = [w \in Win |-> <<>>] /\ toWin = [w \in Win |-> <<>>]
    /\ users = 0 /\ pcause = [w \in Win |-> "none"]
    /\ lastUserPos = P0 /\ echo = FALSE /\ nonUser = 0

(***************************************************************************)
(* The interceptor: send `cur` when it differs from what was last           *)
(* exchanged with main. `c` is why it may differ ("none" means it should    *)
(* not: sending then is an echo).                                           *)
(***************************************************************************)
Emit(w, cur, c) ==
    IF cur # known[w]
    THEN /\ toMain'  = [toMain  EXCEPT ![w] = Append(@, [mid |-> nextMid[w], pos |-> cur, user |-> c = "user"])]
         /\ out'     = [out     EXCEPT ![w] = @ \cup {nextMid[w]}]
         /\ nextMid' = [nextMid EXCEPT ![w] = @ + 1]
         /\ known'   = [known   EXCEPT ![w] = cur]
         /\ echo'    = (echo \/ c = "none")
    ELSE UNCHANGED << toMain, out, nextMid, known, echo >>

(* Show a position main sent: the db takes its blank at once; the engine is told [:show page], snaps it, and
   reports -- always -- on the next frame. *)
Apply(w, pos) ==
    /\ known'    = [known    EXCEPT ![w] = pos]
    /\ blank'    = [blank    EXCEPT ![w] = pos.blank]
    /\ engine'   = [engine   EXCEPT ![w] = Snap(w, pos.page)]
    /\ applying' = [applying EXCEPT ![w] = pos.page]
    /\ pending'  = [pending  EXCEPT ![w] = TRUE]
    /\ pcause'   = [pcause   EXCEPT ![w] = IF Snap(w, pos.page) # pos.page THEN "snap" ELSE "none"]

(* A person moves window w's engine to a page it shows (a key, a click, the overview, a link, find). *)
UserPage(w, q) ==
    /\ phase[w] = "live" /\ users < MaxUser
    /\ Snap(w, q) = q /\ q # engine[w]
    /\ users'   = users + 1
    /\ engine'  = [engine  EXCEPT ![w] = q]
    /\ pending' = [pending EXCEPT ![w] = TRUE]
    /\ pcause'  = [pcause  EXCEPT ![w] = "user"]
    /\ UNCHANGED << mseq, mpos, lastMid, phase, start, mirror, blank, applying, known, seq, ownSeq, out, held,
                    nextMid, snapped, toMain, toWin, lastUserPos, echo, nonUser >>

(* A person blanks or unblanks window w: the db changes at once and the interceptor runs in the same event.
   The page it sends is the page being shown for a push (applying) -- the mirror is stale until that report. *)
UserBlank(w, b) ==
    LET pg == IF ~NoApplying /\ applying[w] # Nil THEN applying[w] ELSE mirror[w]
    IN /\ phase[w] = "live" /\ users < MaxUser /\ b # blank[w] /\ pg # Nil
       /\ users'  = users + 1
       /\ blank'  = [blank EXCEPT ![w] = b]
       /\ Emit(w, [page |-> pg, blank |-> b], "user")
       /\ UNCHANGED << mseq, mpos, lastMid, phase, start, engine, pending, mirror, applying, seq, ownSeq, held,
                       snapped, toWin, pcause, lastUserPos, nonUser >>

(* The engine reports (:slides/changed): the mirror takes its page, a push being shown is done, and the
   interceptor runs. *)
Report(w) ==
    /\ phase[w] = "live" /\ pending[w]
    /\ pending'  = [pending  EXCEPT ![w] = FALSE]
    /\ mirror'   = [mirror   EXCEPT ![w] = engine[w]]
    /\ applying' = [applying EXCEPT ![w] = Nil]
    /\ pcause'   = [pcause   EXCEPT ![w] = "none"]
    /\ Emit(w, [page |-> engine[w], blank |-> blank[w]], pcause[w])
    /\ UNCHANGED << mseq, mpos, lastMid, phase, start, engine, blank, seq, ownSeq, held, snapped, toWin, users,
                    lastUserPos, nonUser >>

(* The audience's deck mounts: the show starts at the start position (the newest main told it). Its known is
   that position, so it sends nothing unless its engine snapped it; it says "ready" on a separate channel that
   moves nothing (not modelled: it changes no position). AnnounceOnMount is the rejected design: known = nil, so
   the first report is sent as a move. *)
Mount(w) ==
    /\ phase[w] = "joining"
    /\ phase'    = [phase    EXCEPT ![w] = "live"]
    /\ seq'      = [seq      EXCEPT ![w] = Max(@, start.seq)]
    /\ known'    = [known    EXCEPT ![w] = IF AnnounceOnMount THEN Nil ELSE start.pos]
    /\ blank'    = [blank    EXCEPT ![w] = start.pos.blank]
    /\ engine'   = [engine   EXCEPT ![w] = Snap(w, start.pos.page)]
    /\ applying' = [applying EXCEPT ![w] = start.pos.page]
    /\ pending'  = [pending  EXCEPT ![w] = TRUE]
    /\ pcause'   = [pcause   EXCEPT ![w] = IF AnnounceOnMount THEN "announce"
                                           ELSE IF Snap(w, start.pos.page) # start.pos.page THEN "snap"
                                           ELSE "none"]
    /\ UNCHANGED << mseq, mpos, lastMid, start, mirror, ownSeq, out, held, nextMid, snapped, toMain, toWin, users,
                    lastUserPos, echo, nonUser >>

(* Window w learns which pages it hides -- its deck model is built, a background scan of a deck without page
   labels ends, or a refresh changes the pages -- and its engine snaps the page it shows, once. A page that
   changed is reported like any other; it is a person's move if a person's move is the report that is due. *)
Rehide(w) ==
    /\ ~snapped[w] /\ SnapOf(w) # SnapNone
    /\ snapped' = [snapped EXCEPT ![w] = TRUE]
    /\ IF phase[w] = "live" /\ SnapOf(w)[engine[w]] # engine[w]
       THEN /\ engine'  = [engine  EXCEPT ![w] = SnapOf(w)[engine[w]]]
            /\ pending' = [pending EXCEPT ![w] = TRUE]
            /\ pcause'  = [pcause  EXCEPT ![w] = IF @ = "user" THEN "user" ELSE "snap"]
       ELSE UNCHANGED << engine, pending, pcause >>
    /\ UNCHANGED << mseq, mpos, lastMid, phase, start, mirror, blank, applying, known, seq, ownSeq, out, held,
                    nextMid, toMain, toWin, users, lastUserPos, echo, nonUser >>

(* Main receives a move from w: a newer mid is accepted (seq + 1, ack to w, push to the other window); an older
   one -- only possible when the channel reorders -- is acknowledged as not accepted and changes nothing. *)
MainRecv(w, i) ==
    LET m == toMain[w][i]
    IN /\ toMain' = [toMain EXCEPT ![w] = Remove(@, i)]
       /\ IF NoMidGuard \/ m.mid > lastMid[w]
          THEN /\ mseq'        = mseq + 1
               /\ mpos'        = m.pos
               /\ lastMid'     = [lastMid EXCEPT ![w] = Max(@, m.mid)]
               /\ lastUserPos' = IF m.user THEN m.pos ELSE lastUserPos
               /\ nonUser'     = IF m.user THEN nonUser ELSE nonUser + 1
               /\ toWin'       = [v \in Win |-> IF v = w THEN Append(toWin[v], Ack(mseq + 1, m.mid, TRUE))
                                                         ELSE Append(toWin[v], Push(mseq + 1, m.pos))]
          ELSE /\ toWin' = [toWin EXCEPT ![w] = Append(@, Ack(0, m.mid, FALSE))]
               /\ UNCHANGED << mseq, mpos, lastMid, lastUserPos, nonUser >>
       /\ UNCHANGED << phase, start, engine, pending, mirror, blank, applying, known, seq, ownSeq, out, held,
                       nextMid, snapped, users, pcause, echo >>

(* A push reaches w. One not newer than what w has seen is dropped (ADR-0046's guard). While joining it only
   moves the start. While w's own move is on the way it is held; otherwise it is shown. *)
RecvPush(w, m) ==
    IF ~NoStaleGuard /\ m.seq <= seq[w]
    THEN UNCHANGED << phase, start, engine, pending, mirror, blank, applying, known, seq, ownSeq, out, held,
                      nextMid, toMain, pcause, echo >>
    ELSE /\ seq' = [seq EXCEPT ![w] = Max(@, m.seq)]
         /\ IF phase[w] = "joining"
            THEN /\ start' = [seq |-> m.seq, pos |-> m.pos]
                 /\ UNCHANGED << phase, engine, pending, mirror, blank, applying, known, ownSeq, out, held, nextMid,
                                 toMain, pcause, echo >>
            ELSE IF out[w] = {} \/ NoHold
                 THEN /\ Apply(w, m.pos)
                      /\ UNCHANGED << phase, start, mirror, ownSeq, out, held, nextMid, toMain, echo >>
                 ELSE /\ held' = [held EXCEPT ![w] = [seq |-> m.seq, pos |-> m.pos]]
                      /\ UNCHANGED << phase, start, engine, pending, mirror, blank, applying, known, ownSeq, out,
                                      nextMid, toMain, pcause, echo >>

(* An ack reaches w: its mid is no longer on the way, whatever its seq. When nothing of w's is on the way any
   more, a held push is shown only if it is newer than w's own last accepted move -- otherwise w's move, which main
   ordered after it, is main's state. *)
RecvAck(w, m) ==
    LET out1 == out[w] \ {m.mid}
        own1 == IF m.accepted THEN Max(ownSeq[w], m.seq) ELSE ownSeq[w]
        seq1 == IF m.accepted THEN Max(seq[w], m.seq) ELSE seq[w]
        show == out1 = {} /\ held[w] # Nil /\ (NoHeldCheck \/ held[w].seq > own1)
    IN /\ out'    = [out    EXCEPT ![w] = out1]
       /\ ownSeq' = [ownSeq EXCEPT ![w] = own1]
       /\ seq'    = [seq    EXCEPT ![w] = seq1]
       /\ held'   = [held   EXCEPT ![w] = IF out1 = {} THEN Nil ELSE @]
       /\ IF show
          THEN Apply(w, held[w].pos)
          ELSE UNCHANGED << known, blank, engine, applying, pending, pcause >>
       /\ UNCHANGED << phase, start, mirror, nextMid, toMain, echo >>

WinRecv(w, i) ==
    LET m == toWin[w][i]
    IN /\ toWin' = [toWin EXCEPT ![w] = Remove(@, i)]
       /\ IF m.type = "push" THEN RecvPush(w, m) ELSE RecvAck(w, m)
       /\ UNCHANGED << mseq, mpos, lastMid, snapped, users, lastUserPos, nonUser >>

Protocol(w) ==
    \/ Report(w)
    \/ Mount(w)
    \/ \E i \in Deliverable(toMain[w]) : MainRecv(w, i)
    \/ \E i \in Deliverable(toWin[w])  : WinRecv(w, i)

People(w) ==
    \/ \E q \in Pages  : UserPage(w, q)
    \/ \E b \in Blanks : UserBlank(w, b)

Next == \E w \in Win : Protocol(w) \/ People(w) \/ Rehide(w)

\* every protocol step is taken when it stays possible; the people, and the windows' learning of their hidden
\* pages, need not happen at all
Spec == Init /\ [][Next]_vars /\ \A w \in Win : WF_vars(Protocol(w))

-----------------------------------------------------------------------------
\* nothing is on the way, nothing is due, both decks are showing
Quiescent ==
    \A w \in Win : /\ phase[w] = "live" /\ toMain[w] = <<>> /\ toWin[w] = <<>>
                   /\ out[w] = {} /\ ~pending[w]

\* P1 -- convergence: when it is quiet, both windows show main's position, and that is what each last exchanged
Converged == \A w \in Win : [page |-> engine[w], blank |-> blank[w]] = mpos /\ known[w] = mpos
Convergence == Quiescent => Converged

\* P2 -- no echo: a window sends a move only for something a person did, or to correct a page its engine snapped
NoEcho == ~echo

\* P3 -- no undo (checked without snapping): when it is quiet, main shows the last move a person made that it
\* accepted -- protocol traffic never undoes a person's move
NoUndo == Quiescent => mpos = lastUserPos

\* P4 -- the seq a window has seen never decreases
SeqMonotone == [][\A w \in Win : seq'[w] >= seq[w] /\ ownSeq'[w] >= ownSeq[w]]_vars

\* P5 -- it always becomes quiet again, and stays so once the people stop
Settles == <>[]Quiescent

\* P6 -- snapping settles: every accepted move that no person made corrects a snapped page, and there are at most
\* MaxPage of them for each thing that can start a chain of corrections (one of the people's actions, the
\* audience's mount, either window's Rehide), because each correction lands on a lower page than the move it
\* corrects (docs/theory/11 §5.6). It needs every snap to move DOWN; the control neg-upsnap shows it.
BoundedCorrections == nonUser <= (MaxUser + 3) * MaxPage

\* a state constraint for neg-upsnap only: an upward snap never settles, so its state space is infinite; the
\* bound is above every seq a downward model reaches (MaxUser person moves and the corrections of P6) plus two
SeqBound == mseq <= MaxUser + (MaxUser + 3) * MaxPage + 2
=============================================================================
