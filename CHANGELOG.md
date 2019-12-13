# Changelog
All version by myself are being displayed as [nyzo version].[my version]

My (YanDevDe aka Chinafreak) Nyzo Donation:

Public Nyzo String:

    id__87v7G1WX91GrzT-30uHMQz4VSZaWRgrouzI0Hx651AXLJHrxP0sZ

Public Identifier Image

![Nyzo Donation](https://i.imgur.com/eW8Z4Cv.png)

Any donations could help me to update more since I'm doing this for living! =) 

___

##  Version 558.1

- Update to version 558

##  Version 550.1

### Method `info`:
##### Return:
- Additional "nyzo_string" for own public NyzoString

### Method `balance`:
##### Sending Data:
- It can have either "identifier" or "nyzo_string", one of the both must be entered.

### Method `broadcast`:
##### Sending Data:
- It can have either "receiver_identifier" or "receiver_nyzo_string" (NyzoString), one of the both must be entered.
-  It can have either "sender_identifier" or "sender_nyzo_string" (NyzoString), one of the both must be entered.
- It can have either "private_seed" (private identifier) or "private_nyzo_string" (private NyzoString), one of the both must be entered.

##### Return:
- "receiver_nyzo_string" and "sender_nyzo_string" added, even if you sent only identifier for receiver and sender

### Method `cycleinfo`:
##### Return:
- Additional "nyzo_string" for node public NyzoString

### Method `block`:
##### Return:
- Additional "sender_nyzo_string" for public sender NyzoString from block
- Additional "receiver_nyzo_string" for public receiver NyzoString from block
___
