# Privacy

qrwent has no account system, server, analytics, advertising, or telemetry in the included application code. The Android app requests camera permission to decode QR frames while scanning. It does not save camera photographs or video.

Sending reads a user-selected file. Android keeps a local prepared copy for sending. Receiving stores incomplete data and resume state in app-private storage; expired incomplete sessions are cleaned after 24 hours. Successfully received files are written to Download/qrwent through MediaStore and remain until deleted by the user. Opening or sharing a file passes it to an external app selected by the user, whose behavior and privacy policy are separate.

The desktop sender reads the selected file locally, processes it in a Worker, and displays its contents as QR codes. The generated HTML embeds its dependencies and blocks network connections through its CSP. It does not upload files.

Visible QR data is not encrypted. Other people or cameras that can see the screen may read the transfer. Browser/Android operating-system behavior and build dependency downloads are outside this app's runtime data handling.
