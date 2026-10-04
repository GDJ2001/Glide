set helperPath to (POSIX path of (path to me)) & "Contents/Resources/uninstall.sh"

set answer to display dialog "Remove Glide from this Mac? Your received files, transfer history, and settings will be kept." buttons {"Cancel", "Uninstall Glide"} default button "Cancel" with icon caution
if button returned of answer is not "Uninstall Glide" then return

try
    do shell script quoted form of helperPath with administrator privileges
    display dialog "Glide was removed. Your files and settings were kept." buttons {"OK"} default button "OK" with icon note
on error errorMessage number errorNumber
    if errorNumber is not -128 then
        display dialog "Glide could not be removed: " & errorMessage buttons {"OK"} default button "OK" with icon stop
    end if
end try
