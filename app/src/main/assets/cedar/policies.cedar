// Cedar policy set for Sankat Setu's mesh flood-control gate.
//
// Scoped to what MessageRouter.handleInboundBytes() actually sees, not the
// PRD's original #chat/#sos/#official channel model — this app has no
// channel concept in the shipped protocol (see mesh/protocol/MessageType.kt):
// every inbound packet is one of a small set of kinds (public broadcast,
// directed/Noise-encrypted chat, SOS broadcast, IOU envelope, announce).
// The policy below gates on *that* real vocabulary, per-sender, at the one
// choke point every packet passes through — see docs/adr/0017 and
// handoff.md's Cedar integration note.
//
// principal = Peer::"<hex peer id>"   (the packet's senderId)
// action    = Action::"relay"         (the only action this app authorizes)
// resource  = MessageKind::"public" | "directed" | "sos" | "iou" | "announce"
// context   = { messagesLastMinute: Long }  (count for that sender+kind, computed by the caller)

// Default: any peer may have any message kind relayed.
permit(
  principal,
  action == Action::"relay",
  resource
);

// Flood control on public chat broadcasts: 30/minute per sender.
forbid(
  principal,
  action == Action::"relay",
  resource == MessageKind::"public"
) when {
  context.messagesLastMinute > 30
};

// SOS is not a chat channel — tighter cap, but still real headroom for a
// person retrying because they're not sure the first one went out.
forbid(
  principal,
  action == Action::"relay",
  resource == MessageKind::"sos"
) when {
  context.messagesLastMinute > 5
};

// IOU vouchers involve money-adjacent claims — cap distinct from chat so a
// malfunctioning or malicious sender can't spam fabricated debts.
forbid(
  principal,
  action == Action::"relay",
  resource == MessageKind::"iou"
) when {
  context.messagesLastMinute > 10
};

// A peer explicitly blocked (Settings > blocked peers — not yet built, see
// docs/adr/0017) cannot have anything relayed, regardless of kind or rate.
forbid(
  principal,
  action == Action::"relay",
  resource
) when {
  principal in Group::"blocked"
};
