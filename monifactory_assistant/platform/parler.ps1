param([string]$TextFile)
# Lit le fichier texte a voix haute avec la voix francaise de Windows.
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$fr = $synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'fr-FR' } | Select-Object -First 1
if ($fr) { $synth.SelectVoice($fr.VoiceInfo.Name) }
$synth.Rate = 1
$synth.Speak([IO.File]::ReadAllText($TextFile, [Text.Encoding]::UTF8))
