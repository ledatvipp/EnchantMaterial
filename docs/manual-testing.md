# Manual Testing - Rebirth Placeholders

These steps verify that the `EnchantMaterialPlaceholder` class reads rebirth data from `rebirth.yml` using the updated `rebirth.rebirth.*` paths.

1. **Prepare the configuration**
   - Open `plugins/EnchantMaterial/rebirth.yml` on the test server.
   - Pick a rebirth tier (e.g. level `2`) and set distinctive values for:
     - `rebirth.rebirth.levels.2.required-level`
     - `rebirth.rebirth.levels.2.required-money`
     - `rebirth.rebirth.levels.2.success-rate`
     - `rebirth.rebirth.max-level`
   - Save the file and reload the plugin with `/enchantmaterial reload`.

2. **Assign player data**
   - Ensure the test player has a matching rebirth level in the database (or mock it by running the plugin's debug command if available).
   - The player should have `rebirth_level = 1` so that the next tier evaluated by the placeholders is level `2`.

3. **Verify placeholders with PlaceholderAPI**
   - Run the following commands in-game or from the console:
     - `/papi parse <player> %enchantmaterial_rebirth_required_level%`
     - `/papi parse <player> %enchantmaterial_rebirth_required_money%`
     - `/papi parse <player> %enchantmaterial_rebirth_success_rate%`
     - `/papi parse <player> %enchantmaterial_rebirth_max_level%`
   - Each command should reflect the values that were configured in `rebirth.yml`.

4. **Check progress placeholders**
   - Update `rebirth.rebirth.max-level` to a different number and reload the plugin.
   - Run `/papi parse <player> %enchantmaterial_rebirth_progress%` and `/papi parse <player> %enchantmaterial_rebirth_progress_percent%`.
   - Confirm that the numerator uses the player's current rebirth level and the denominator matches the updated `rebirth.rebirth.max-level` value.

5. **Reset configuration**
   - Restore the original `rebirth.yml` values when finished testing to avoid affecting other environments.
