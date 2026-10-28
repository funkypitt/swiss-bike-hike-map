package btools.router;

/**
 * Swiss Bike-Hike Map addition: reads the voice hints of a track without exposing BRouter's
 * package-private fields. Each hint becomes three ints: index in the track, command, exit number.
 */
public final class TurnHints {
  private TurnHints() {}

  public static final int C = VoiceHint.C, TL = VoiceHint.TL, TSLL = VoiceHint.TSLL, TSHL = VoiceHint.TSHL,
    TR = VoiceHint.TR, TSLR = VoiceHint.TSLR, TSHR = VoiceHint.TSHR, KL = VoiceHint.KL, KR = VoiceHint.KR,
    TLU = VoiceHint.TLU, TRU = VoiceHint.TRU, OFFR = VoiceHint.OFFR, RNDB = VoiceHint.RNDB, RNLB = VoiceHint.RNLB,
    TU = VoiceHint.TU, BL = VoiceHint.BL, EL = VoiceHint.EL, ER = VoiceHint.ER, END = VoiceHint.END;

  public static int[] of(OsmTrack t) {
    if (t == null || t.voiceHints == null) return new int[0];
    int n = t.voiceHints.list.size();
    int[] out = new int[n * 3];
    for (int i = 0; i < n; i++) {
      VoiceHint h = t.voiceHints.list.get(i);
      out[i * 3] = h.indexInTrack;
      out[i * 3 + 1] = h.cmd;
      out[i * 3 + 2] = h.roundaboutExit;
    }
    return out;
  }
}
