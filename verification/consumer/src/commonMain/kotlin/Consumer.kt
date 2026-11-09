import dev.shareinbox.ShareInbox

suspend fun consumePublishedPackage(directory: String): String {
    val inbox = ShareInbox(directory)
    inbox.receive(text = "published artifact", deliveryId = "smoke")
    val item = inbox.pending().single()
    check(inbox.acknowledge(item.id))
    check(inbox.pending().isEmpty())
    return item.text!!
}
